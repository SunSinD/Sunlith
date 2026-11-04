package io.github.sunsind.sunlith;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class HandoffFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "text/calendar"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
        cursor.addRow(new Object[] { "handoff-fixture.ics", 248 });
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/fixture.ics".equals(uri.getPath()) || !"r".equals(mode)) throw new FileNotFoundException("Only the synthetic calendar fixture can be read.");
        File file = new File(getContext().getCacheDir(), "handoff-fixture.ics");
        String fixture = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Sunlith//Instrumentation fixture//EN\r\nBEGIN:VEVENT\r\nUID:sunlith-handoff-fixture\r\nDTSTART:20270120T150000Z\r\nDTEND:20270120T160000Z\r\nSUMMARY:Shared fixture appointment\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(fixture.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
