package com.raulsc.lenguareaccion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

// Runs in the test APK's own process, which intentionally has no app/Kotlin dependencies.
public class TestVideoProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public String getType(Uri uri) { return "video/x-matroska"; }
    public Uri insert(Uri uri, ContentValues values) { return null; }
    public int delete(Uri uri, String selection, String[] args) { return 0; }
    public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    private void row(MatrixCursor cursor, String[] columns, String id, String name, boolean directory) {
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: values[i] = id; break;
                case OpenableColumns.DISPLAY_NAME: values[i] = name; break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    values[i] = directory ? DocumentsContract.Document.MIME_TYPE_DIR : "video/x-matroska"; break;
                case OpenableColumns.SIZE: values[i] = 7L; break;
            }
        }
        cursor.addRow(values);
    }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME} : projection;
        MatrixCursor result = new MatrixCursor(columns);
        if ("children".equals(uri.getLastPathSegment())) {
            if ("root".equals(uri.getPathSegments().get(3))) row(result, columns, "episodes", "Series", true);
            else { row(result, columns, "a", "episode-a.mkv", false); row(result, columns, "b", "episode-b.mkv", false); }
        } else row(result, columns, uri.getLastPathSegment(), "episode-" + uri.getLastPathSegment() + ".mkv", false);
        return result;
    }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ("slow".equals(uri.getLastPathSegment())) {
            try { Thread.sleep(12000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        File file = new File(getContext().getCacheDir(), "fixture-" + uri.getLastPathSegment() + ".mkv");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(("video " + uri.getLastPathSegment()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) { throw new FileNotFoundException(e.getMessage()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
