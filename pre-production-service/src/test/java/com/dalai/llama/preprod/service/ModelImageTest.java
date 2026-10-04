package com.dalai.llama.preprod.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Frame generation sends several images in one Gemini request (the frame, each character's face,
 * the product). At full size a few phone photos exceeded the 20 MB request limit and every retry
 * failed alike; and the type was guessed from the file name.
 */
class ModelImageTest {

    /** Noise compresses badly -- the size of a real high-resolution photo. */
    private static byte[] photo(int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static byte[] payload(String dataUri) {
        return Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1));
    }

    @Test
    void fourPhoneSizePhotosFitInOneRequestOnceSizedForTheModel() throws Exception {
        byte[] phonePhoto = photo(4000, 3000, "png");
        assertThat(phonePhoto.length).isGreaterThan(20_000_000 / 4);

        long requestBytes = 0;
        for (int i = 0; i < 4; i++) {
            requestBytes += ModelImage.dataUri(phonePhoto).length();
        }

        assertThat(requestBytes).isLessThan(20_000_000);
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(payload(ModelImage.dataUri(phonePhoto))));
        assertThat(Math.max(sent.getWidth(), sent.getHeight())).isEqualTo(ModelImage.MAX_SIDE);
        assertThat(sent.getWidth() * 3).isEqualTo(sent.getHeight() * 4);
    }

    @Test
    void aSmallImageGoesThroughUntouched() throws Exception {
        byte[] small = photo(64, 48, "jpg");

        String uri = ModelImage.dataUri(small);

        assertThat(uri).startsWith("data:image/jpeg;base64,");
        assertThat(payload(uri)).isEqualTo(small);
    }

    @Test
    void theTypeComesFromTheBytesNotTheFileName() throws Exception {
        assertThat(ModelImage.dataUri(photo(32, 32, "png"))).startsWith("data:image/png;");
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 1, 2, 3};
        assertThat(ModelImage.dataUri(webp)).startsWith("data:image/webp;");
        assertThat(ModelImage.dataUri(new byte[0])).isNull();
    }
}
