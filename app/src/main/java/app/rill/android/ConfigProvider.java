package app.rill.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Only immutable, explicitly granted configuration snapshots can be read. */
public final class ConfigProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  private File file(Uri uri) throws FileNotFoundException {
    java.util.List<String> parts = uri.getPathSegments();
    if (parts.size() != 2
        || !parts.get(0).matches("[a-f0-9-]{36}")
        || !parts.get(1).equals("Rill-Clash.yaml")) throw new FileNotFoundException();
    File f = new File(new File(getContext().getFilesDir(), "shared"), parts.get(0) + ".yaml");
    if (!f.isFile()) throw new FileNotFoundException();
    return f;
  }

  public String getType(Uri uri) {
    return "application/yaml";
  }

  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort) {
    try {
      File f = file(uri);
      if (columns == null)
        columns = new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
      MatrixCursor c = new MatrixCursor(columns);
      Object[] values = new Object[columns.length];
      for (int i = 0; i < columns.length; i++)
        values[i] =
            OpenableColumns.DISPLAY_NAME.equals(columns[i])
                ? "Rill-Clash.yaml"
                : OpenableColumns.SIZE.equals(columns[i]) ? f.length() : null;
      c.addRow(values);
      return c;
    } catch (IOException e) {
      return null;
    }
  }

  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
