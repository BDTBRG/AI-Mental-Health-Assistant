package com.amha.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Serializes the demo seed across API replicas sharing the same MySQL database. */
@Slf4j
@Component
public class DemoSeedAdvisoryLock {

    private static final String LOCK_NAME = "amha-demo-data-seed";
    private static final int LOCK_WAIT_SECONDS = 60;

    private final DataSource dataSource;

    public DemoSeedAdvisoryLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void runWithLock(Runnable initialization) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Demo seed lock must run inside a transaction"
            );
        }

        Connection lockConnection = acquireLockConnection();
        if (lockConnection == null) {
            // H2 backs the single-process test suite and has no MySQL advisory locks.
            initialization.run();
            return;
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            releaseAndClose(lockConnection);
                        }
                    }
            );
        } catch (RuntimeException error) {
            releaseAndClose(lockConnection);
            throw error;
        }

        initialization.run();
    }

    private Connection acquireLockConnection() {
        Connection connection = null;
        boolean acquired = false;
        try {
            // Keep this dedicated connection open until the seed transaction completes.
            // MySQL advisory locks belong to a connection, not a transaction.
            connection = dataSource.getConnection();
            String databaseProduct = connection.getMetaData().getDatabaseProductName();
            if ("H2".equalsIgnoreCase(databaseProduct)) {
                closeAfterAcquireFailure(connection);
                return null;
            }
            if (!"MySQL".equalsIgnoreCase(databaseProduct)
                    && !"MariaDB".equalsIgnoreCase(databaseProduct)) {
                throw new IllegalStateException(
                        "Demo seed advisory locking requires MySQL or MariaDB"
                );
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT GET_LOCK(?, ?)"
            )) {
                statement.setString(1, LOCK_NAME);
                statement.setInt(2, LOCK_WAIT_SECONDS);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new IllegalStateException(
                                "Timed out waiting for the demo seed lock"
                        );
                    }
                    acquired = true;
                }
            }
            return connection;
        } catch (SQLException error) {
            discardAfterAcquireFailure(connection, acquired);
            throw new IllegalStateException(
                    "Unable to acquire the demo seed lock", error
            );
        } catch (RuntimeException error) {
            discardAfterAcquireFailure(connection, acquired);
            throw error;
        }
    }

    private void discardAfterAcquireFailure(Connection connection, boolean acquired) {
        if (connection == null) {
            return;
        }
        if (acquired) {
            abortConnection(connection);
        } else {
            closeAfterAcquireFailure(connection);
        }
    }

    private void releaseAndClose(Connection connection) {
        boolean released = false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT RELEASE_LOCK(?)"
        )) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                released = result.next() && result.getInt(1) == 1;
            }
            if (!released) {
                log.error("MySQL did not confirm release of the demo seed lock");
                abortConnection(connection);
                return;
            }
        } catch (SQLException error) {
            log.error(
                    "Failed to release the demo seed lock ({}); discarding its connection",
                    error.getClass().getSimpleName()
            );
            abortConnection(connection);
            return;
        }

        try {
            connection.close();
        } catch (SQLException error) {
            log.error(
                    "Failed to close the demo seed lock connection ({}); discarding it",
                    error.getClass().getSimpleName()
            );
            abortConnection(connection);
        }
    }

    private void closeAfterAcquireFailure(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException error) {
            log.warn(
                    "Failed to close an unacquired demo seed lock connection ({})",
                    error.getClass().getSimpleName()
            );
            abortConnection(connection);
        }
    }

    private void abortConnection(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException error) {
            log.error(
                    "Failed to abort the demo seed lock connection ({})",
                    error.getClass().getSimpleName()
            );
            try {
                connection.close();
            } catch (SQLException closeError) {
                log.error(
                        "Failed to close the demo seed lock connection ({})",
                        closeError.getClass().getSimpleName()
                );
            }
        }
    }
}
