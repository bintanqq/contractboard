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
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Manages all SQLite database operations.
 * All write/read operations are dispatched asynchronously via the Bukkit scheduler,
 * with callbacks executed on the main thread when needed.
 */
public class DatabaseManager {

    private final ContractBoard plugin;
    private Connection connection;

    public DatabaseManager(ContractBoard plugin) {
        this.plugin = plugin;
    }

    // ---- Connection ----

    /**
     * Opens the SQLite connection and creates tables if they don't exist.
     * Called synchronously on enable.
     */
    public boolean connect() {
        try {
            String dbFile = plugin.getConfigManager().getDatabaseFile();
            File file = new File(plugin.getDataFolder(), dbFile);
            file.getParentFile().mkdirs();

            String url = "jdbc:sqlite:" + file.getAbsolutePath();
            connection = DriverManager.getConnection(url);
            createTables();
            plugin.getLogger().info("Connected to SQLite database.");
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to connect to database.", e);
            return false;
        }
    }

    public void disconnect() {
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
            // Contracts table
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

            // Player stats
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

            // Mail
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS mail (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    recipient_uuid TEXT NOT NULL,
                    amount REAL NOT NULL,
                    description TEXT,
                    created_at INTEGER NOT NULL
                )
            """);
        }
    }

    // ---- Async helper ----

    /**
     * Runs a database task asynchronously, then optionally calls back on the main thread.
     */
    private <T> void async(java.util.concurrent.Callable<T> task, Consumer<T> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                T result = task.call();
                if (callback != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Async DB task failed.", e);
            }
        });
    }

    // ---- Contract CRUD ----

    /**
     * Inserts a new contract and returns it (with auto-generated ID) via callback.
     */
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

                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) {
                    int id = keys.getInt(1);
                    // Return a new instance with the generated ID
                    Contract inserted = new Contract(id, contract.getType(),
                            contract.getContractorUUID(), contract.getContractorName(),
                            contract.getReward(), contract.getTaxPaid(),
                            contract.getCreatedAt(), contract.getExpiresAt(), contract.getMetadata());
                    inserted.setStatus(contract.getStatus());
                    if (contract.getWorkerUUID() != null) {
                        inserted.setWorker(contract.getWorkerUUID(), contract.getWorkerName());
                    }
                    return inserted;
                }
            }
            return null;
        }, callback);
    }

    /**
     * Updates the status, worker, and metadata of a contract.
     */
    public void updateContract(Contract contract) {
        async(() -> {
            String sql = """
                UPDATE contracts SET status=?, worker_uuid=?, worker_name=?, metadata=?
                WHERE id=?
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, contract.getStatus().name());
                ps.setString(2, contract.getWorkerUUID() != null ? contract.getWorkerUUID().toString() : null);
                ps.setString(3, contract.getWorkerName());
                ps.setString(4, contract.getMetadata());
                ps.setInt(5, contract.getId());
                ps.executeUpdate();
            }
            return null;
        }, null);
    }

    /**
     * Loads all active (OPEN, ACCEPTED, PAUSED) contracts into memory on startup.
     */
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

    /**
     * Loads contracts by contractor UUID.
     */
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

        String workerUUID = rs.getString("worker_uuid");
        String workerName = rs.getString("worker_name");
        if (workerUUID != null) {
            c.setWorker(UUID.fromString(workerUUID), workerName);
        }
        return c;
    }

    // ---- Player Stats ----

    public void upsertPlayerStats(PlayerStats stats) {
        async(() -> {
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
                ps.setString(1, stats.getUuid().toString());
                ps.setString(2, stats.getName());
                ps.setDouble(3, stats.getTotalSpent());
                ps.setDouble(4, stats.getTotalEarned());
                ps.setInt(5, stats.getContractsPosted());
                ps.setInt(6, stats.getContractsCompleted());
                ps.executeUpdate();
            }
            return null;
        }, null);
    }

    public void getTopBySpent(int limit, Consumer<List<PlayerStats>> callback) {
        async(() -> {
            List<PlayerStats> list = new ArrayList<>();
            String sql = "SELECT * FROM player_stats ORDER BY total_spent DESC LIMIT ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
            String sql = "SELECT * FROM player_stats ORDER BY total_earned DESC LIMIT ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
            String sql = "SELECT * FROM player_stats WHERE uuid=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
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

    public void insertMail(UUID recipientUUID, double amount, String description, Consumer<MailEntry> callback) {
        async(() -> {
            String sql = "INSERT INTO mail (recipient_uuid, amount, description, created_at) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, recipientUUID.toString());
                ps.setDouble(2, amount);
                ps.setString(3, description);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) {
                    return new MailEntry(keys.getInt(1), recipientUUID, amount, description, System.currentTimeMillis());
                }
            }
            return null;
        }, callback);
    }

    public void getMailForPlayer(UUID uuid, Consumer<List<MailEntry>> callback) {
        async(() -> {
            List<MailEntry> entries = new ArrayList<>();
            String sql = "SELECT * FROM mail WHERE recipient_uuid=? ORDER BY created_at ASC";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
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

    public void deleteMail(int mailId) {
        async(() -> {
            String sql = "DELETE FROM mail WHERE id=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, mailId);
                ps.executeUpdate();
            }
            return null;
        }, null);
    }

    public void deleteAllMailForPlayer(UUID uuid) {
        async(() -> {
            String sql = "DELETE FROM mail WHERE recipient_uuid=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        }, null);
    }
}
