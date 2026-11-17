package com.premisave.messenger.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Draws QR codes as PNG images. */
public final class QrCodes {

    public static final int MIN_SIZE = 128;
    public static final int MAX_SIZE = 1024;

    private QrCodes() {
    }

    /** A square PNG of the text as a QR code, size pixels wide. Size is kept between MIN_SIZE and MAX_SIZE. */
    public static byte[] png(String text, int size) {
        int px = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.CHARACTER_SET, "UTF-8",
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN, 2);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException e) {
            throw new IllegalStateException("Could not draw the QR code", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}