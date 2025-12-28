package forge.interfaces;

import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;

public interface IDeviceAdapter {
    boolean isConnectedToInternet();
    boolean isConnectedToWifi();
    boolean isTablet();
    String getDownloadsDir();
    String getVersionString();
    String getLatestChanges(String commitsAtom, Date buildDateOriginal, Date maxDate);
    String getReleaseTag(String releaseAtom);
    boolean openFile(String filename);
    void setLandscapeMode(boolean landscapeMode);
    void preventSystemSleep(boolean preventSleep);
    void restart();
    void exit();
    void closeSplashScreen();
    void convertToJPEG(InputStream input, OutputStream output) throws IOException;
    Pair<Integer, Integer> getRealScreenSize(boolean real);
    ArrayList<String> getGamepads();
    Object getUpnpPlatformService(); // Returns UpnpServiceConfiguration on supported platforms, null on iOS
    boolean needFileAccess();
    void requestFileAcces();

    Set<String> LWJGL_SUPPORTED_AUDIO_TYPES = java.util.Collections.unmodifiableSet(
        new java.util.HashSet<>(java.util.Arrays.asList(".wav", ".mp3", ".ogg"))
    );
    default boolean isSupportedAudioFormat(File file) {
        try {
            if (file == null) {
                return false;
            }
            String path = file.getPath();
            if (path == null || path.isEmpty()) {
                return false;
            }
            String lowerPath = path.toLowerCase();
            if (lowerPath == null) {
                return false;
            }
            for (String ext : LWJGL_SUPPORTED_AUDIO_TYPES) {
                if (ext != null && lowerPath.endsWith(ext)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // iOS safety: catch any unexpected exceptions
            return false;
        }
    }
}
