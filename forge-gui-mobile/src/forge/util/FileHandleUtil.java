package forge.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

/**
 * Utility class for creating FileHandle objects in a platform-compatible way.
 *
 * On iOS, resources are bundled into the app and must be accessed using Gdx.files.internal().
 * User data should be accessed using Gdx.files.local() or Gdx.files.external().
 * On desktop, traditional file paths work fine.
 */
public class FileHandleUtil {

    /**
     * Create a FileHandle for bundled resources (read-only files shipped with the app).
     *
     * On iOS: Uses Gdx.files.internal() to access bundled resources
     * On Desktop: Uses regular FileHandle with path
     *
     * @param path Path to the resource file (e.g., "res/adventure/common/config.json")
     * @return FileHandle for the resource
     */
    public static FileHandle getInternal(String path) {
        if (Gdx.files != null) {
            return Gdx.files.internal(path);
        }
        return new FileHandle(path);
    }

    /**
     * Create a FileHandle for user data (writable files in user's directory).
     *
     * On iOS: Uses Gdx.files.local() for user-writable storage
     * On Desktop: Uses regular FileHandle with path
     *
     * @param path Path to the user data file (e.g., "settings.json", "save/game.dat")
     * @return FileHandle for the user data file
     */
    public static FileHandle getLocal(String path) {
        if (Gdx.files != null) {
            return Gdx.files.local(path);
        }
        return new FileHandle(path);
    }

    /**
     * Create a FileHandle for external storage (SD card, external directory).
     *
     * On iOS: Uses Gdx.files.external()
     * On Desktop: Uses regular FileHandle with path
     *
     * @param path Path to the external file
     * @return FileHandle for the external file
     */
    public static FileHandle getExternal(String path) {
        if (Gdx.files != null) {
            return Gdx.files.external(path);
        }
        return new FileHandle(path);
    }

    /**
     * Determine if we should use Gdx.files.internal() for bundled resources.
     * This is true on iOS and Android where resources are bundled into the app.
     *
     * @return true if internal file API should be used
     */
    public static boolean shouldUseInternalFiles() {
        return Gdx.files != null;
    }
}
