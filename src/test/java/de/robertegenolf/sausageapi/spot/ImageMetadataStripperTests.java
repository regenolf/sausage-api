package de.robertegenolf.sausageapi.spot;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageMetadataStripperTests {

	private static final String SECRET = "GPS 50.123456 6.987654";

	@Test
	void jpegVerliertExifAberBehaeltAusrichtung() throws Exception {
		byte[] jpeg = image("jpg");
		byte[] withExif = insertAfterSoi(jpeg, exifSegment(6, SECRET));
		assertThat(contains(withExif, SECRET)).isTrue();

		byte[] stripped = ImageMetadataStripper.strip(withExif, "image/jpeg");

		assertThat(contains(stripped, SECRET)).isFalse();
		assertThat(contains(stripped, "Exif\0\0")).isTrue();
		assertThat(ImageIO.read(new ByteArrayInputStream(stripped))).isNotNull();
		// Orientation-Eintrag (Tag 0x0112, Typ SHORT, Wert 6) ist noch da
		assertThat(indexOf(stripped, new byte[] {0x01, 0x12, 0x00, 0x03, 0, 0, 0, 1, 0x00, 0x06}))
				.isGreaterThan(0);
	}

	@Test
	void jpegOhneDrehungBekommtKeinExif() throws Exception {
		byte[] stripped = ImageMetadataStripper.strip(insertAfterSoi(image("jpg"), exifSegment(1, SECRET)),
				"image/jpeg");
		assertThat(contains(stripped, "Exif")).isFalse();
		assertThat(ImageIO.read(new ByteArrayInputStream(stripped))).isNotNull();
	}

	@Test
	void pngVerliertTextChunks() throws Exception {
		byte[] png = image("png");
		byte[] withText = insertPngChunk(png, "tEXt", ("Comment\0" + SECRET).getBytes(StandardCharsets.ISO_8859_1));
		assertThat(contains(withText, SECRET)).isTrue();

		byte[] stripped = ImageMetadataStripper.strip(withText, "image/png");

		assertThat(contains(stripped, SECRET)).isFalse();
		assertThat(stripped).isEqualTo(png);
		assertThat(ImageIO.read(new ByteArrayInputStream(stripped))).isNotNull();
	}

	@Test
	void webpVerliertExifUndFlags() throws Exception {
		byte[] vp8x = new byte[10];
		vp8x[0] = 0x08 | 0x04 | 0x10; // EXIF, XMP, Alpha
		byte[] webp = riff(chunk("VP8X", vp8x), chunk("VP8L", new byte[] {0x2F, 1, 2}),
				chunk("EXIF", SECRET.getBytes(StandardCharsets.ISO_8859_1)), chunk("XMP ", new byte[] {1, 2}));

		byte[] stripped = ImageMetadataStripper.strip(webp, "image/webp");

		assertThat(contains(stripped, SECRET)).isFalse();
		assertThat(contains(stripped, "XMP ")).isFalse();
		byte[] expected = riff(chunk("VP8X", flags(vp8x, (byte) 0x10)), chunk("VP8L", new byte[] {0x2F, 1, 2}));
		assertThat(stripped).isEqualTo(expected);
	}

	@Test
	void kaputteDateienWerdenAbgelehnt() {
		assertThatThrownBy(() -> ImageMetadataStripper.strip(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
				(byte) 0xE1, 0x7F, 0x7F, 0}, "image/jpeg"))
				.isInstanceOf(ImageMetadataStripper.InvalidImageException.class);
		assertThatThrownBy(() -> ImageMetadataStripper.strip(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10,
				0x7F, 0, 0, 0, 'I', 'D', 'A', 'T'}, "image/png"))
				.isInstanceOf(ImageMetadataStripper.InvalidImageException.class);
	}

	private static byte[] image(String format) throws IOException {
		BufferedImage img = new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB);
		img.setRGB(3, 3, 0xFF0000);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, format, out);
		return out.toByteArray();
	}

	/** EXIF-APP1 mit Orientation und einem ASCII-Tag (ImageDescription) als Ersatz für GPS-Daten. */
	private static byte[] exifSegment(int orientation, String text) {
		byte[] ascii = (text + "\0").getBytes(StandardCharsets.ISO_8859_1);
		ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 2 * 12 + 4 + ascii.length).order(ByteOrder.LITTLE_ENDIAN);
		tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
		tiff.putShort((short) 2);
		tiff.putShort((short) 0x010E).putShort((short) 2).putInt(ascii.length).putInt(8 + 2 + 24 + 4);
		tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
		tiff.putInt(0);
		tiff.put(ascii);
		byte[] header = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
		int length = 2 + header.length + tiff.capacity();
		return ByteBuffer.allocate(2 + length).put((byte) 0xFF).put((byte) 0xE1).putShort((short) length)
				.put(header).put(tiff.array()).array();
	}

	private static byte[] insertAfterSoi(byte[] jpeg, byte[] segment) {
		return ByteBuffer.allocate(jpeg.length + segment.length).put(jpeg, 0, 2).put(segment)
				.put(jpeg, 2, jpeg.length - 2).array();
	}

	private static byte[] insertPngChunk(byte[] png, String type, byte[] data) {
		int ihdrEnd = 8 + 12 + 13;
		byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		ByteBuffer chunk = ByteBuffer.allocate(12 + data.length).putInt(data.length).put(typeBytes).put(data)
				.putInt((int) crc.getValue());
		return ByteBuffer.allocate(png.length + chunk.capacity()).put(png, 0, ihdrEnd).put(chunk.array())
				.put(png, ihdrEnd, png.length - ihdrEnd).array();
	}

	private static byte[] chunk(String type, byte[] data) {
		ByteBuffer b = ByteBuffer.allocate(8 + data.length + (data.length & 1)).order(ByteOrder.LITTLE_ENDIAN);
		b.put(type.getBytes(StandardCharsets.ISO_8859_1)).putInt(data.length).put(data);
		return b.array();
	}

	private static byte[] riff(byte[]... chunks) {
		int size = 0;
		for (byte[] c : chunks) {
			size += c.length;
		}
		ByteBuffer b = ByteBuffer.allocate(12 + size).order(ByteOrder.LITTLE_ENDIAN);
		b.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(4 + size).put("WEBP".getBytes(StandardCharsets.ISO_8859_1));
		for (byte[] c : chunks) {
			b.put(c);
		}
		return b.array();
	}

	private static byte[] flags(byte[] vp8x, byte value) {
		byte[] copy = vp8x.clone();
		copy[0] = value;
		return copy;
	}

	private static boolean contains(byte[] data, String text) {
		return indexOf(data, text.getBytes(StandardCharsets.ISO_8859_1)) >= 0;
	}

	private static int indexOf(byte[] data, byte[] needle) {
		outer:
		for (int i = 0; i <= data.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (data[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
