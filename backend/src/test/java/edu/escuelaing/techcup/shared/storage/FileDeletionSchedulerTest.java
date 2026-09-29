package edu.escuelaing.techcup.shared.storage;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Binaries go away only once the transaction that stopped referencing them has committed. */
@ExtendWith(MockitoExtension.class)
class FileDeletionSchedulerTest {

    @Mock
    private FileStorage fileStorage;
    @InjectMocks
    private FileDeletionScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void insideATransactionTheFileIsDeletedAfterCommitOnly() {
        TransactionSynchronizationManager.initSynchronization();

        scheduler.deleteAfterCommit("old-file");
        verify(fileStorage, never()).delete("old-file");

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(fileStorage).delete("old-file");
    }

    @Test
    void aRolledBackTransactionKeepsTheFile() {
        TransactionSynchronizationManager.initSynchronization();

        scheduler.deleteAfterCommit("old-file");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(fileStorage, never()).delete("old-file");
    }

    @Test
    void outsideATransactionTheFileIsDeletedRightAway() {
        scheduler.deleteAfterCommit("old-file");

        verify(fileStorage).delete("old-file");
    }

    @Test
    void nothingHappensForAMissingReference() {
        scheduler.deleteAfterCommit(null);
        scheduler.deleteAfterCommit(" ");

        verify(fileStorage, never()).delete(org.mockito.ArgumentMatchers.any());
    }
}
