package edu.escuelaing.techcup.shared.storage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Deletes binaries <em>after</em> the database transaction that stopped referencing them has
 * committed. Deleting inside the transaction would lose the old file if the transaction were
 * rolled back afterwards (the row would still point at a binary that no longer exists).
 */
@Component
public class FileDeletionScheduler {

    private final FileStorage fileStorage;

    public FileDeletionScheduler(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * Schedules the deletion of {@code fileId} for the commit of the current transaction, or
     * deletes it right away when no transaction is active. A null or blank id is ignored.
     */
    public void deleteAfterCommit(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fileStorage.delete(fileId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fileStorage.delete(fileId);
            }
        });
    }
}
