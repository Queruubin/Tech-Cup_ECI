package edu.escuelaing.techcup.shared.storage;

import com.mongodb.client.gridfs.model.GridFSFile;
import edu.escuelaing.techcup.shared.config.AppProperties;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link FileStorage} adapter backed by MongoDB GridFS. The stored content type is the one
 * proven by the file's magic bytes, never the one declared by the client. A file stored inside a
 * database transaction is deleted again if that transaction rolls back.
 */
@Component
public class GridFsFileStorage implements FileStorage {

    static final String META_CONTENT_TYPE = "contentType";
    static final String META_KIND = "kind";

    private static final Logger log = LoggerFactory.getLogger(GridFsFileStorage.class);

    private final GridFsTemplate gridFsTemplate;
    private final long maxFileSizeBytes;

    public GridFsFileStorage(GridFsTemplate gridFsTemplate, AppProperties properties) {
        this.gridFsTemplate = gridFsTemplate;
        this.maxFileSizeBytes = properties.storage().maxFileSizeBytes();
    }

    @Override
    public String store(FileUpload upload, FileKind kind, FileOwner owner) {
        if (owner == null) {
            throw new IllegalArgumentException("Every stored file needs an owner");
        }
        InputStream content = upload.content().markSupported()
                ? upload.content()
                : new BufferedInputStream(upload.content());
        String contentType = validate(upload, kind, content);
        Document metadata = new Document(META_CONTENT_TYPE, contentType)
                .append(META_KIND, owner.category().name())
                .append(owner.category().ownerField(), owner.ownerId());
        ObjectId id = gridFsTemplate.store(content, upload.filename(), contentType, metadata);
        String fileId = id.toHexString();
        deleteIfRolledBack(fileId);
        return fileId;
    }

    /**
     * GridFS is not part of the JPA transaction: when the use case that stored the file rolls back
     * (a business rule refused it after the upload), no row will ever point at the binary, so it
     * is deleted. The counterpart of {@link FileDeletionScheduler#deleteAfterCommit}.
     */
    private void deleteIfRolledBack(String fileId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    delete(fileId);
                }
            }
        });
    }

    @Override
    public Optional<StoredFile> find(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            return Optional.empty();
        }
        GridFSFile file = gridFsTemplate.findOne(byId(id));
        if (file == null) {
            return Optional.empty();
        }
        GridFsResource resource = gridFsTemplate.getResource(file);
        try {
            Document metadata = file.getMetadata();
            String contentType = metadata != null ? metadata.getString(META_CONTENT_TYPE) : null;
            return Optional.of(new StoredFile(id, file.getFilename(),
                    contentType != null ? contentType : "application/octet-stream",
                    file.getLength(), ownerOf(metadata), resource.getInputStream()));
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not open stored file " + id, ex);
        }
    }

    @Override
    public void delete(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            return;
        }
        try {
            gridFsTemplate.delete(byId(id));
        } catch (RuntimeException ex) {
            // Deletions run after the owning transaction completed; a missing binary must never
            // undo a business operation, so the orphan is only logged.
            log.warn("Could not delete stored file {}: {}", id, ex.getClass().getSimpleName());
        }
    }

    static FileOwner ownerOf(Document metadata) {
        if (metadata == null || metadata.getString(META_KIND) == null) {
            return null;
        }
        try {
            FileOwner.Category category = FileOwner.Category.valueOf(metadata.getString(META_KIND));
            Number ownerId = metadata.get(category.ownerField(), Number.class);
            return ownerId == null ? null : new FileOwner(category, ownerId.longValue());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(new ObjectId(id)));
    }

    /**
     * Checks size, the declared type and the magic bytes, and returns the content type the bytes
     * prove. {@code content} must support mark/reset: the header is read and the stream rewound.
     */
    private String validate(FileUpload upload, FileKind kind, InputStream content) {
        if (upload.size() <= 0) {
            throw new InvalidFileException("Debe adjuntar un archivo; el archivo enviado está vacío.");
        }
        if (upload.size() > maxFileSizeBytes) {
            throw new InvalidFileException("El archivo supera el tamaño máximo de " + maxFileSizeBytes + " bytes.");
        }
        if (!kind.accepts(upload.contentType())) {
            throw new InvalidFileException("El tipo de archivo '" + upload.contentType()
                    + "' no es compatible. Formatos permitidos: " + String.join(", ", kind.contentTypes()) + ".");
        }
        String declared = upload.contentType().toLowerCase(Locale.ROOT);
        String detected = ContentSniffer.detect(readHeader(content))
                .orElseThrow(() -> new InvalidFileException(
                        "El contenido del archivo no corresponde a ningún formato permitido ("
                                + String.join(", ", kind.contentTypes()) + ")."));
        if (!detected.equals(declared)) {
            throw new InvalidFileException("El contenido del archivo no corresponde al tipo declarado '"
                    + declared + "'.");
        }
        return detected;
    }

    private static byte[] readHeader(InputStream content) {
        byte[] header = new byte[ContentSniffer.HEADER_LENGTH];
        content.mark(ContentSniffer.HEADER_LENGTH + 1);
        try {
            int read = content.readNBytes(header, 0, header.length);
            content.reset();
            return read == header.length ? header : Arrays.copyOf(header, read);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read uploaded file", ex);
        }
    }
}
