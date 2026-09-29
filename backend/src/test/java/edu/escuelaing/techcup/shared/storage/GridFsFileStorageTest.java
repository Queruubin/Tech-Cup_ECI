package edu.escuelaing.techcup.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.gridfs.model.GridFSFile;
import edu.escuelaing.techcup.shared.config.AppProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

/** Uploads are validated by their real bytes and stored with their owner. */
@ExtendWith(MockitoExtension.class)
class GridFsFileStorageTest {

    @Mock
    private GridFsTemplate gridFsTemplate;

    private GridFsFileStorage storage;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.Jwt("x".repeat(40), 60),
                List.of("http://localhost:5173"),
                List.of("escuelaing.edu.co"),
                "UTC",
                new AppProperties.Storage(1024),
                new AppProperties.Bootstrap("admin@escuelaing.edu.co", "Str0ngAdminPass"));
        storage = new GridFsFileStorage(gridFsTemplate, properties);
    }

    @Test
    void storesTheSniffedTypeAndTheOwnerAsMetadata() throws IOException {
        ObjectId id = new ObjectId();
        when(gridFsTemplate.store(any(InputStream.class), eq("photo.png"), eq("image/png"), any(Document.class)))
                .thenReturn(id);

        String stored = storage.store(upload("photo.png", "image/PNG", TestFiles.PNG), FileKind.IMAGE, FileOwner.photo(10L));

        assertThat(stored).isEqualTo(id.toHexString());
        ArgumentCaptor<Document> metadata = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<InputStream> content = ArgumentCaptor.forClass(InputStream.class);
        verify(gridFsTemplate).store(content.capture(), eq("photo.png"), eq("image/png"), metadata.capture());
        assertThat(metadata.getValue().getString("contentType")).isEqualTo("image/png");
        assertThat(metadata.getValue().getString("kind")).isEqualTo("PHOTO");
        assertThat(metadata.getValue().getLong("userId")).isEqualTo(10L);
        // The header read for sniffing was rewound: the whole file is what gets stored.
        assertThat(content.getValue().readAllBytes()).isEqualTo(TestFiles.PNG);
    }

    @Test
    void rejectsContentThatDoesNotMatchTheDeclaredType() {
        assertThatThrownBy(() -> storage.store(upload("photo.png", "image/png", TestFiles.JPEG), FileKind.IMAGE,
                FileOwner.photo(10L)))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("no corresponde al tipo declarado");
        verify(gridFsTemplate, never()).store(any(InputStream.class), anyString(), anyString(), any(Document.class));
    }

    @Test
    void rejectsContentOfAnUnknownFormatWhateverTheDeclaredType() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes();

        assertThatThrownBy(() -> storage.store(upload("photo.png", "image/png", html), FileKind.IMAGE,
                FileOwner.photo(10L)))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("ningún formato permitido");
    }

    @Test
    void rejectsADeclaredTypeTheKindDoesNotAccept() {
        assertThatThrownBy(() -> storage.store(upload("rules.pdf", "application/pdf", TestFiles.PDF), FileKind.IMAGE,
                FileOwner.venue(1L)))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("no es compatible");
    }

    @Test
    void rejectsFilesOverTheSizeLimit() {
        FileUpload upload = new FileUpload("big.png", "image/png", 2048, new ByteArrayInputStream(TestFiles.PNG));

        assertThatThrownBy(() -> storage.store(upload, FileKind.IMAGE, FileOwner.photo(10L)))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("tamaño máximo");
    }

    @Test
    void readsTheOwnerBackFromMetadata() throws IOException {
        ObjectId id = new ObjectId();
        Document metadata = new Document("contentType", "application/pdf").append("kind", "RECEIPT").append("teamId", 5L);
        GridFSFile file = new GridFSFile(new BsonObjectId(id), "receipt.pdf", 13, 255, new Date(), metadata);
        when(gridFsTemplate.findOne(any(Query.class))).thenReturn(file);
        when(gridFsTemplate.getResource(file)).thenReturn(new GridFsResource(file, new ByteArrayInputStream(TestFiles.PDF)));

        StoredFile stored = storage.find(id.toHexString()).orElseThrow();

        assertThat(stored.owner()).isEqualTo(FileOwner.receipt(5L));
        assertThat(stored.isPdf()).isTrue();
        assertThat(stored.content().readAllBytes()).isEqualTo(TestFiles.PDF);
    }

    @Test
    void filesStoredBeforeOwnerMetadataHaveNoOwner() {
        assertThat(GridFsFileStorage.ownerOf(new Document("contentType", "image/png"))).isNull();
        assertThat(GridFsFileStorage.ownerOf(new Document("kind", "SOMETHING_ELSE"))).isNull();
        assertThat(GridFsFileStorage.ownerOf(null)).isNull();
    }

    @Test
    void deleteIgnoresMalformedIdsAndRemovesValidOnes() {
        storage.delete("not-an-object-id");
        verify(gridFsTemplate, never()).delete(any(Query.class));

        storage.delete(new ObjectId().toHexString());
        verify(gridFsTemplate).delete(any(Query.class));
    }

    private static FileUpload upload(String name, String contentType, byte[] bytes) {
        return new FileUpload(name, contentType, bytes.length, new ByteArrayInputStream(bytes));
    }
}
