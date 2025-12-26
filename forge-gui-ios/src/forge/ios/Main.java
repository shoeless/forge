package forge.ios;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

import org.apache.commons.lang3.tuple.Pair;
import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.foundation.NSTimeZone;
import org.robovm.apple.foundation.NSBundle;
import org.robovm.apple.uikit.UIApplication;
import org.robovm.apple.uikit.UIPasteboard;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.backends.iosrobovm.IOSFiles;

import forge.Forge;
import forge.interfaces.IDeviceAdapter;

public class Main extends IOSApplication.Delegate {

    // Static initializer runs when class is loaded, before main()
    static {
        System.err.println("FORGE: Static initializer starting");
        try {
            // Create a custom timezone without file system access
            NSTimeZone systemTimeZone = NSTimeZone.getSystemTimeZone();
            if (systemTimeZone != null) {
                String tzName = systemTimeZone.getName();
                long offsetSeconds = systemTimeZone.getSecondsFromGMT();
                int offsetMillis = (int) (offsetSeconds * 1000);

                // Set both the property AND the default timezone programmatically
                System.setProperty("user.timezone", tzName != null ? tzName : "UTC");
                TimeZone.setDefault(new SimpleTimeZone(offsetMillis, tzName != null ? tzName : "UTC"));
                System.err.println("FORGE: Timezone set to " + tzName);
            } else {
                // Fallback if systemTimeZone is null
                System.setProperty("user.timezone", "America/Los_Angeles");
                TimeZone.setDefault(new SimpleTimeZone(-8 * 3600 * 1000, "America/Los_Angeles"));
                System.err.println("FORGE: Timezone set to fallback PST");
            }
        } catch (Throwable e) {
            System.err.println("FORGE: Exception in static initializer: " + e.getMessage());
            e.printStackTrace();
            // Catch everything including Errors to prevent static initializer failure
            try {
                System.setProperty("user.timezone", "America/Los_Angeles");
                TimeZone.setDefault(new SimpleTimeZone(-8 * 3600 * 1000, "America/Los_Angeles"));
            } catch (Throwable ignored) {
                // If even the fallback fails, continue anyway
            }
        }
        System.err.println("FORGE: Static initializer completed");
    }

    private void copyEssentialResources(final String assetsDir) {
        System.err.println("FORGE: copyEssentialResources() starting");
        try {
            String bundlePath = NSBundle.getMainBundle().getBundlePath();
            File bundleResDir = new File(bundlePath, "res");

            System.err.println("FORGE: Bundle res dir: " + bundleResDir.getAbsolutePath());

            if (bundleResDir.exists() && bundleResDir.isDirectory()) {
                // Only copy the languages directory (9 small files) to avoid watchdog timeout
                // The rest of resources will be accessed directly from the bundle
                File bundleLangDir = new File(bundleResDir, "languages");
                File docsLangDir = new File(assetsDir + "/res", "languages");

                if (bundleLangDir.exists() && !docsLangDir.exists()) {
                    System.err.println("FORGE: Copying languages directory...");
                    copyDirectory(bundleLangDir, docsLangDir);
                    System.err.println("FORGE: Languages copied successfully");
                } else if (docsLangDir.exists()) {
                    System.err.println("FORGE: Languages directory already exists");
                } else {
                    System.err.println("FORGE: Languages directory not found in bundle");
                }
            } else {
                System.err.println("FORGE: No bundled resources found at: " + bundleResDir.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("FORGE: Error copying essential resources: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void copyFile(final File source, final File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
        }
    }

    private void copyDirectory(final File source, final File dest) throws IOException {
        if (!dest.exists()) {
            dest.mkdirs();
        }

        File[] files = source.listFiles();
        if (files != null) {
            for (File file : files) {
                File destFile = new File(dest, file.getName());
                if (file.isDirectory()) {
                    copyDirectory(file, destFile);
                } else {
                    // Only copy if destination doesn't exist (don't overwrite user changes)
                    if (!destFile.exists()) {
                        copyFile(file, destFile);
                        System.err.println("FORGE: Copied " + file.getName());
                    }
                }
            }
        }
    }

    @Override
    protected IOSApplication createApplication() {
        System.err.println("FORGE: createApplication() starting");
        try {
            // Use the app bundle as assetsDir so resources are read directly from there
            // This avoids copying 16,662 files and hitting watchdog timeout
            String bundlePath = NSBundle.getMainBundle().getBundlePath();
            // Ensure path ends with / so relative paths are appended correctly
            final String assetsDir = bundlePath.endsWith("/") ? bundlePath : bundlePath + "/";
            System.err.println("FORGE: Assets dir (bundle): " + assetsDir);

            // Set writable directories for iOS using libGDX IOSFiles (Documents directory)
            // This avoids iOS sandbox violations when trying to write to the read-only app bundle
            String documentsPath = new IOSFiles().getExternalStoragePath();
            System.err.println("FORGE: Documents dir (writable): " + documentsPath);
            System.setProperty("forge.ios.userDir", documentsPath);
            System.setProperty("forge.ios.cacheDir", documentsPath + "cache/");

            final IOSApplicationConfiguration config = new IOSApplicationConfiguration();
            config.useAccelerometer = false;
            config.useCompass = false;
            config.useAudio = false;  // Disable audio to avoid OpenAL initialization crash
            System.err.println("FORGE: Calling Forge.getApp()");
            final ApplicationListener app = Forge.getApp(null, new IOSClipboard(), new IOSAdapter(), assetsDir, false, false, 0, false, 0);
            System.err.println("FORGE: app class is: " + app.getClass().getName());
            System.err.flush();

            System.err.println("FORGE: Creating IOSApplication");
            final IOSApplication iosApp = new IOSApplication(app, config);
            System.err.println("FORGE: createApplication() completed");
            return iosApp;
        } catch (Throwable e) {
            System.err.println("FORGE: Exception in createApplication(): " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    public static void main(String[] args) {
        System.err.println("FORGE: main() starting");
        try {
            // Use iOS NSTimeZone API to avoid sandbox violations when accessing /etc/timezone
            NSTimeZone systemTimeZone = NSTimeZone.getSystemTimeZone();
            System.setProperty("user.timezone", systemTimeZone.getName());

            final NSAutoreleasePool pool = new NSAutoreleasePool();
            System.err.println("FORGE: Calling UIApplication.main()");
            UIApplication.main(args, null, Main.class);
            System.err.println("FORGE: UIApplication.main() returned");
            pool.close();
        } catch (Throwable e) {
            System.err.println("FORGE: Exception in main(): " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    //special clipboard that works on iOS
    private static final class IOSClipboard implements com.badlogic.gdx.utils.Clipboard {
        @Override
        public boolean hasContents() {
            return UIPasteboard.getGeneralPasteboard().toString().length() > 0;
        }

        @Override
        public String getContents() {
            return UIPasteboard.getGeneralPasteboard().getString();
        }

        @Override
        public void setContents(final String contents0) {
            UIPasteboard.getGeneralPasteboard().setString(contents0);
        }
    }

    private static final class IOSAdapter implements IDeviceAdapter {
        @Override
        public boolean isConnectedToInternet() {
            return true;
        }

        @Override
        public boolean isConnectedToWifi() {
            return true;
        }

        @Override
        public String getDownloadsDir() {
            return new IOSFiles().getExternalStoragePath();
        }

        @Override
        public String getVersionString() {
            return "0.0";
        }

        @Override
        public String getLatestChanges(String commitsAtom, Date buildDateOriginal, Date maxDate) {
            return "";
        }

        @Override
        public String getReleaseTag(String releaseAtom) {
            return "";
        }

        @Override
        public boolean openFile(final String filename) {
            return new IOSFiles().local(filename).exists();
        }

        @Override
        public void setLandscapeMode(final boolean landscapeMode) {
            // TODO implement this
        }

        @Override
        public void preventSystemSleep(boolean preventSleep) {
            // TODO implement this
        }

        @Override
        public boolean isTablet() {
            return Gdx.graphics.getWidth() > Gdx.graphics.getHeight();
        }

        @Override
        public void restart() {
            // Not possible on iOS
        }

        @Override
        public void exit() {
            // Not possible on iOS
        }

        @Override
        public void closeSplashScreen() {
            //only for desktop mobile-dev
        }

        @Override
        public void convertToJPEG(InputStream input, OutputStream output) throws IOException {

        }

        @Override
        public Pair<Integer, Integer> getRealScreenSize(boolean real) {
            return Pair.of(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        }

        @Override
        public ArrayList<String> getGamepads() {
            return new ArrayList<>();
        }

        @Override
        public Object getUpnpPlatformService() {
            // not used on iOS
            return null;
        }

        @Override
        public boolean needFileAccess() {
            return false;
        }

        @Override
        public void requestFileAcces() {

        }
    }
}