package me.bintanq.database;

import me.bintanq.ContractBoard;
import me.bintanq.model.Contract;
import me.bintanq.model.Contract.ContractStatus;
import me.bintanq.model.Contract.ContractType;
import me.bintanq.model.MailEntry;
import me.bintanq.model.PlayerStats;
import org.bukkit.Bukkit;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Manages all SQLite database operations.
 *
 * Performance decisions:
 *  - Uses a single-threaded {@link ExecutorService} for ALL DB work.
 *    This avoids the "thread storm" problem where every operation spawns a
 *    new Bukkit async task. One thread serializes all queries safely.
 *  - SQLite WAL mode enabled for better concurrent read performance.
 *  - Prepared statements are created fresh per call (connection is single-threaded,
 *    so no need for a pool).
 *  - Callbacks are dispatched back to the Bukkit main thread via runTask().
 */
public class DatabaseManager {

    private final ContractBoard plugin;
    private Connection connection;

    /**
     * Single-threaded executor — guarantees all DB operations run sequentially
     * on one background thread, eliminating race conditions on the connection.
     */
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ContractBoard-DB");
        t.setDaemon(true);
        return t;
    });

    public DatabaseManager(ContractBoard plugin) {
        this.plugin = plugin;
    }

    // ---- Connection ----

    /**
     * Opens SQLite connection and creates schema. Runs synchronously on enable.
     */
    public boolean connect() {
        try {
            String dbFile = plugin.getConfigManager().getDatabaseFile();
            File file = new File(plugin.getDataFolder(), dbFile);
            file.getParentFile().mkdirs();

            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());

            // Enable WAL mode — dramatically reduces lock contention for concurrent reads
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                // Synchronous=NORMAL is safe with WAL and much faster than FULL
                s.execute("PRAGMA synchronous=NORMAL");
                // Keep 64MB page cache in memory
                s.execute("PRAGMA cache_size=-65536");
            }

            createTables();
            plugin.getLogger().info("Connected to SQLite (WAL mode).");
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to connect to database.", e);
            return false;
        }
    }

    /**
     * Gracefully shuts down the executor then closes the connection.
     */
    public void disconnect() {
        dbExecutor.shutdown();
        try {
            // Wait up to 5 seconds for pending writes to flush
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error closing database.", e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS contracts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'OPEN',
                    contractor_uuid TEXT NOT NULL,
                    contractor_name TEXT NOT NULL,
                    worker_uuid TEXT,
                    worker_name TEXT,
                    reward REAL NOT NULL,
                    tax_paid REAL NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    metadata TEXT
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_stats (
                    uuid TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    total_spent REAL DEFAULT 0,
                    total_earned REAL DEFAULT 0,
                    contracts_posted INTEGER DEFAULT 0,
                    contracts_completed INTEGER DEFAULT 0
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS mail (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    recipient_uuid TEXT NOT NULL,
                    amount REAL NOT NULL,
                    description TEXT,
                    created_at INTEGER NOT NULL
                )
            """);
            // Index for common queries
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_contracts_status ON contracts(status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mail_recipient ON mail(recipient_uuid)");
        }
    }

    // ---- Async execution helpers ----

    /**
     * Submits a DB task to the single-threaded executor.
     * On completion, the callback (if non-null) is dispatched on the Bukkit main thread.
     */
    private <T> void async(Callable<T> task, Consumer<T> callback) {
        dbExecutor.submit(() -> {
            try {
                T result = task.call();
                if (callback != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "DB task failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Fire-and-forget variant for writes that need no callback.
     */
    private void asyncWrite(ThrowingRunnable task) {
        dbExecutor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "DB write failed: " + e.getMessage(), e);
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ---- Contract CRUD ----

    public void insertContract(Contract contract, Consumer<Contract> callback) {
        async(() -> {
            String sql = """
                INSERT INTO contracts (type, status, contractor_uuid, contractor_name,
                    worker_uuid, worker_name, reward, tax_paid, created_at, expires_at, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, contract.getType().name());
                ps.setString(2, contract.getStatus().name());
                ps.setString(3, contract.getContractorUUID().toString());
                ps.setString(4, contract.getContractorName());
                ps.setString(5, contract.getWorkerUUID() != null ? contract.getWorkerUUID().toString() : null);
                ps.setString(6, contract.getWorkerName());
                ps.setDouble(7, contract.getReward());
                ps.setDouble(8, contract.getTaxPaid());
                ps.setLong(9, contract.getCreatedAt());
                ps.setLong(10, contract.getExpiresAt());
                ps.setString(11, contract.getMetadata());
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        int id = keys.getInt(1);
                        Contract inserted = new Contract(id, contract.getType(),
                                contract.getContractorUUID(), contract.getContractorName(),
                                contract.getReward(), contract.getTaxPaid(),
                                contract.getCreatedAt(), contract.getExpiresAt(),
                                contract.getMetadata());
                        inserted.setStatus(contract.getStatus());
                        if (contract.getWorkerUUID() != null) {
                            inserted.setWorker(contract.getWorkerUUID(), contract.getWorkerName());
                        }
                        return inserted;
                    }
                }
            }
            return null;
        }, callback);
    }

    /**
     * Updates status, worker, and metadata. Fire-and-forget (no callback needed).
     */
    public void updateContract(Contract contract) {
        // Snapshot mutable fields so the async thread doesn't read a later mutation
        int id = contract.getId();
        String status = contract.getStatus().name();
        String workerUUID = contract.getWorkerUUID() != null ? contract.getWorkerUUID().toString() : null;
        String workerName = contract.getWorkerName();
        String metadata = contract.getMetadata();

        asyncWrite(() -> {
            String sql = "UPDATE contracts SET status=?, worker_uuid=?, worker_name=?, metadata=? WHERE id=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setString(2, workerUUID);
                ps.setString(3, workerName);
                ps.setString(4, metadata);
                ps.setInt(5, id);
                ps.executeUpdate();
            }
        });
    }

    public void loadActiveContracts(Consumer<List<Contract>> callback) {
        async(() -> {
            List<Contract> contracts = new ArrayList<>();
            String sql = "SELECT * FROM contracts WHERE status IN ('OPEN','ACCEPTED','PAUSED')";
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    contracts.add(parseContract(rs));
                }
            }
            return contracts;
        }, callback);
    }

    public void loadContractsByContractor(UUID uuid, Consumer<List<Contract>> callback) {
        async(() -> {
            List<Contract> contracts = new ArrayList<>();
            String sql = "SELECT * FROM contracts WHERE contractor_uuid=? ORDER BY created_at DESC";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) contracts.add(parseContract(rs));
                }
            }
            return contracts;
        }, callback);
    }

    private Contract parseContract(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        ContractType type = ContractType.valueOf(rs.getString("type"));
        UUID contractorUUID = UUID.fromString(rs.getString("contractor_uuid"));
        String contractorName = rs.getString("contractor_name");
        double reward = rs.getDouble("reward");
        double taxPaid = rs.getDouble("tax_paid");
        long createdAt = rs.getLong("created_at");
        long expiresAt = rs.getLong("expires_at");
        String metadata = rs.getString("metadata");

        Contract c = new Contract(id, type, contractorUUID, contractorName,
                reward, taxPaid, createdAt, expiresAt, metadata);
        c.setStatus(ContractStatus.valueOf(rs.getString("status")));

        String workerUUIDStr = rs.getString("worker_uuid");
        if (workerUUIDStr != null) {
            c.setWorker(UUID.fromString(workerUUIDStr), rs.getString("worker_name"));
        }
        return c;
    }

    // ---- Player Stats ----

    public void upsertPlayerStats(UUID uuid, String name, double totalSpent, double totalEarned,
                                  int contractsPosted, int contractsCompleted) {
        asyncWrite(() -> {
            String sql = """
                INSERT INTO player_stats (uuid, name, total_spent, total_earned, contracts_posted, contracts_completed)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    name=excluded.name,
                    total_spent=excluded.total_spent,
                    total_earned=excluded.total_earned,
                    contracts_posted=excluded.contracts_posted,
                    contracts_completed=excluded.contracts_completed
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setDouble(3, totalSpent);
                ps.setDouble(4, totalEarned);
                ps.setInt(5, contractsPosted);
                ps.setInt(6, contractsCompleted);
                ps.executeUpdate();
            }
        });
    }

    public void getTopBySpent(int limit, Consumer<List<PlayerStats>> callback) {
        async(() -> {
            List<PlayerStats> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM player_stats ORDER BY total_spent DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(parseStats(rs));
                }
            }
            return list;
        }, callback);
    }

    public void getTopByEarned(int limit, Consumer<List<PlayerStats>> callback) {
        async(() -> {
            List<PlayerStats> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM player_stats ORDER BY total_earned DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(parseStats(rs));
                }
            }
            return list;
        }, callback);
    }

    public void getPlayerStats(UUID uuid, Consumer<PlayerStats> callback) {
        async(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM player_stats WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return parseStats(rs);
                }
            }
            return null;
        }, callback);
    }

    private PlayerStats parseStats(ResultSet rs) throws SQLException {
        return new PlayerStats(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getDouble("total_spent"),
                rs.getDouble("total_earned"),
                rs.getInt("contracts_posted"),
                rs.getInt("contracts_completed")
        );
    }

    // ---- Mail ----

    /**
     * Inserts a mail entry. Callback is optional.
     */
    public void insertMail(UUID recipientUUID, double amount, String description,
                           Consumer<MailEntry> callback) {
        long now = System.currentTimeMillis();
        String uuidStr = recipientUUID.toString();

        async(() -> {
            String sql = "INSERT INTO mail (recipient_uuid, amount, description, created_at) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uuidStr);
                ps.setDouble(2, amount);
                ps.setString(3, description);
                ps.setLong(4, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return new MailEntry(keys.getInt(1), recipientUUID, amount, description, now);
                    }
                }
            }
            return null;
        }, callback);
    }

    public void getMailForPlayer(UUID uuid, Consumer<List<MailEntry>> callback) {
        async(() -> {
            List<MailEntry> entries = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM mail WHERE recipient_uuid=? ORDER BY created_at ASC")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new MailEntry(
                                rs.getInt("id"),
                                UUID.fromString(rs.getString("recipient_uuid")),
                                rs.getDouble("amount"),
                                rs.getString("description"),
                                rs.getLong("created_at")
                        ));
                    }
                }
            }
            return entries;
        }, callback);
    }

    public void deleteAllMailForPlayer(UUID uuid) {
        String uuidStr = uuid.toString();
        asyncWrite(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM mail WHERE recipient_uuid=?")) {
                ps.setString(1, uuidStr);
                ps.executeUpdate();
            }
        });
    }

    public void deleteMail(int mailId) {
        asyncWrite(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM mail WHERE id=?")) {
                ps.setInt(1, mailId);
                ps.executeUpdate();
            }
        });
    }
}