package com.lyreo.learner.api;

import com.lyreo.contracts.errors.RequestValidationException;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

final class LearnerAvatarImage {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int OUTPUT_SIZE = 256;

    private LearnerAvatarImage() {}

    static byte[] normalize(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new RequestValidationException("Choose a JPEG or PNG image up to 2 MiB");
        }
        try (var contents = file.getInputStream(); var input = new MemoryCacheImageInputStream(contents)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw unsupported();
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) throw unsupported();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096) {
                    throw new RequestValidationException("Avatar dimensions must be at most 4096 by 4096 pixels");
                }

                int side = Math.min(width, height);
                var readParams = reader.getDefaultReadParam();
                readParams.setSourceRegion(new Rectangle((width - side) / 2, (height - side) / 2, side, side));
                int stride = Math.max(1, side / (OUTPUT_SIZE * 2));
                readParams.setSourceSubsampling(stride, stride, 0, 0);
                var source = reader.read(0, readParams);
                var square = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB);
                var graphics = square.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(source, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE, null);
                } finally {
                    graphics.dispose();
                }
                // Re-encode from pixels to discard metadata such as EXIF.
                var png = new ByteArrayOutputStream();
                ImageIO.write(square, "png", png);
                return png.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException invalidImage) {
            throw unsupported();
        }
    }

    static ResponseEntity<byte[]> response(byte[] png) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff").body(png);
    }

    private static RequestValidationException unsupported() {
        return new RequestValidationException("Avatar must be a readable JPEG or PNG image");
    }
}
