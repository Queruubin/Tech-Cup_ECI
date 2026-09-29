package edu.escuelaing.techcup.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentSnifferTest {

    @Test
    void recognisesTheFourAcceptedFormats() {
        assertThat(ContentSniffer.detect(TestFiles.PNG)).contains("image/png");
        assertThat(ContentSniffer.detect(TestFiles.JPEG)).contains("image/jpeg");
        assertThat(ContentSniffer.detect(TestFiles.WEBP)).contains("image/webp");
        assertThat(ContentSniffer.detect(TestFiles.PDF)).contains("application/pdf");
    }

    @Test
    void rejectsAnythingElse() {
        assertThat(ContentSniffer.detect("<html><body>".getBytes())).isEmpty();
        assertThat(ContentSniffer.detect(new byte[]{0x4D, 0x5A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00})).isEmpty();
        assertThat(ContentSniffer.detect(new byte[0])).isEmpty();
        assertThat(ContentSniffer.detect(null)).isEmpty();
    }

    @Test
    void aRiffContainerThatIsNotWebpIsNotAnImage() {
        byte[] wave = "RIFF\0\0\0\0WAVE".getBytes();

        assertThat(ContentSniffer.detect(wave)).isEmpty();
    }
}
