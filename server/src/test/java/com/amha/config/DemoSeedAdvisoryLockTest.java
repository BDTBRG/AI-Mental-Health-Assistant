package com.amha.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoSeedAdvisoryLockTest {

    private DataSource dataSource;
    private Connection lockConnection;
    private DatabaseMetaData metadata;
    private PreparedStatement acquireStatement;
    private PreparedStatement releaseStatement;
    private ResultSet acquired;
    private ResultSet released;
    private DemoSeedAdvisoryLock lock;

    @BeforeEach
    void setUp() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        dataSource = mock(DataSource.class);
        lockConnection = mock(Connection.class);
        metadata = mock(DatabaseMetaData.class);
        acquireStatement = mock(PreparedStatement.class);
        releaseStatement = mock(PreparedStatement.class);
        acquired = mock(ResultSet.class);
        released = mock(ResultSet.class);
        when(lockConnection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        lock = new DemoSeedAdvisoryLock(dataSource);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void releasesTheConnectionScopedLockOnlyAfterTransactionCompletion() throws Exception {
        Runnable seed = mock(Runnable.class);
        when(dataSource.getConnection()).thenReturn(lockConnection);
        when(lockConnection.prepareStatement(anyString()))
                .thenReturn(acquireStatement, releaseStatement);
        when(acquireStatement.executeQuery()).thenReturn(acquired);
        when(acquired.next()).thenReturn(true);
        when(acquired.getInt(1)).thenReturn(1);
        when(releaseStatement.executeQuery()).thenReturn(released);
        when(released.next()).thenReturn(true);
        when(released.getInt(1)).thenReturn(1);

        assertDoesNotThrow(() -> lock.runWithLock(seed));

        verify(seed).run();
        verify(lockConnection, never()).prepareStatement("SELECT RELEASE_LOCK(?)");
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_COMMITTED
                )
        );
        verify(lockConnection).prepareStatement("SELECT GET_LOCK(?, ?)");
        verify(acquireStatement).setString(1, "amha-demo-data-seed");
        verify(acquireStatement).setInt(2, 60);
        verify(lockConnection).prepareStatement("SELECT RELEASE_LOCK(?)");
        verify(releaseStatement).setString(1, "amha-demo-data-seed");
        verify(lockConnection).close();
    }

    @Test
    void doesNotRunSeedWhenAnotherInstanceDoesNotReleaseTheLockInTime() throws Exception {
        Runnable seed = mock(Runnable.class);
        when(dataSource.getConnection()).thenReturn(lockConnection);
        when(lockConnection.prepareStatement(anyString())).thenReturn(acquireStatement);
        when(acquireStatement.executeQuery()).thenReturn(acquired);
        when(acquired.next()).thenReturn(true);
        when(acquired.getInt(1)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> lock.runWithLock(seed));

        verify(seed, never()).run();
        verify(lockConnection).close();
        verify(lockConnection, never()).prepareStatement("SELECT RELEASE_LOCK(?)");
    }

    @Test
    void usesTheTransactionWithoutAMysqlLockForTheLocalH2TestDatabase() throws Exception {
        Runnable seed = mock(Runnable.class);
        when(dataSource.getConnection()).thenReturn(lockConnection);
        when(metadata.getDatabaseProductName()).thenReturn("H2");

        assertDoesNotThrow(() -> lock.runWithLock(seed));

        verify(seed).run();
        verify(lockConnection).close();
        verify(lockConnection, never()).prepareStatement(anyString());
    }
}
