package forge.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.net.HttpStatus;
import forge.Forge;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LibGDXImageFetcher extends ImageFetcher {
    @Override
    protected Runnable getDownloadTask(String[] downloadUrls, String destPath, Runnable notifyObservers) {
        return new LibGDXDownloadTask(downloadUrls, destPath, notifyObservers);
    }

    private static class LibGDXDownloadTask implements Runnable {
        private final String[] downloadUrls;
        private final String destPath;
        private final Runnable notifyObservers;

        LibGDXDownloadTask(String[] downloadUrls, String destPath, Runnable notifyObservers) {
            this.downloadUrls = downloadUrls;
            this.destPath = destPath;
            this.notifyObservers = notifyObservers;
        }

        /**
         * iOS fix: Convert absolute path to relative path for libGDX compatibility.
         * libGDX's AssetManager on iOS requires relative paths from the local directory.
         * On iOS, files are in Documents/ but libGDX local base is Library/local/,
         * so we need to construct a relative path like ../../Documents/...
         */
        private String toRelativePath(String absolutePath) {
            // Extract the portion after the app container UUID
            // Path format: .../Application/UUID/Documents/cache/pics/...
            // We want: ../../Documents/cache/pics/...

            int documentsIndex = absolutePath.indexOf("/Documents/");
            if (documentsIndex != -1) {
                // Found Documents directory - construct relative path from Library/local
                return "../../Documents" + absolutePath.substring(documentsIndex + "/Documents".length());
            }

            int libraryIndex = absolutePath.indexOf("/Library/");
            if (libraryIndex != -1) {
                // File is in Library somewhere - might already be accessible
                return absolutePath.substring(absolutePath.indexOf("/Library/") + "/Library/".length());
            }

            return absolutePath;
        }

        private boolean doFetch(String urlToDownload) throws IOException {
            System.err.println("=== iOS Download Debug ===");
            System.err.println("Starting download for: " + urlToDownload);

            if (disableHostedDownload && urlToDownload.startsWith(ForgeConstants.URL_CARDFORGE)) {
                System.err.println("Skipping cardforge download (disabled)");
                return false;
            }

            String newdespath = urlToDownload.contains(".fullborder.") || urlToDownload.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD) ?
                    TextUtil.fastReplace(destPath, ".full.", ".fullborder.") : destPath;
            if (!newdespath.contains(".full") && urlToDownload.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD) &&
                    !destPath.startsWith(ForgeConstants.CACHE_TOKEN_PICS_DIR) && !destPath.startsWith(ForgeConstants.CACHE_PLANECHASE_PICS_DIR))
                newdespath = newdespath.replace(".jpg", ".fullborder.jpg");

            System.err.println("Destination path (absolute): " + newdespath);

            // iOS fix: Convert to relative path for libGDX compatibility
            String relativePath = toRelativePath(newdespath);
            System.err.println("Destination path (relative): " + relativePath);

            FileHandle destFile = Gdx.files.local(relativePath + ".tmp");
            System.err.println("Temp file path: " + destFile.file().getAbsolutePath());
            System.err.println("Parent directory: " + destFile.parent().file().getAbsolutePath());

            // Create parent directory
            destFile.parent().mkdirs();
            System.err.println("Parent directory mkdirs() called");
            System.err.println("Parent exists: " + destFile.parent().exists());
            System.err.println("Parent is directory: " + destFile.parent().isDirectory());

            URL url = new URL(urlToDownload);
            System.err.println("Opening connection to: " + url);

            java.net.URLConnection c = url.openConnection();
            c.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
            c.setConnectTimeout(10000); // 10 second timeout
            c.setReadTimeout(30000); // 30 second read timeout

            System.err.println("Getting input stream...");
            InputStream is = c.getInputStream();
            System.err.println("Input stream obtained successfully");

            final long[] bytesWritten = {0};
            try(OutputStream out = new FileOutputStream(destFile.file())) {
                System.err.println("Output stream created, starting conversion...");

                // Track bytes for debugging
                OutputStream debugOut = new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        out.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        out.write(b, off, len);
                        bytesWritten[0] += len;
                    }
                };

                Forge.getDeviceAdapter().convertToJPEG(is, debugOut);
                is.close();
                System.err.println("Conversion complete, bytes written: " + bytesWritten[0]);
            }

            System.err.println("Temp file exists: " + destFile.exists());
            System.err.println("Temp file size: " + destFile.length() + " bytes");

            FileHandle finalFile = Gdx.files.local(relativePath);
            System.err.println("Moving to final location: " + finalFile.file().getAbsolutePath());
            destFile.moveTo(finalFile);

            System.err.println("Final file exists: " + finalFile.exists());
            System.err.println("Final file size: " + finalFile.length() + " bytes");
            System.err.println("Download completed successfully!");
            System.err.println("=== End Download Debug ===");

            GuiBase.getInterface().invokeInEdtLater(notifyObservers);
            return true;
        }

        /**
         * iOS-friendly download using libGDX Net API.
         * Uses platform-specific network implementation that handles threading and SSL properly.
         */
        private boolean doFetchWithGdxNet(String urlToDownload) throws IOException {
            System.err.println("=== libGDX Net Download ===");
            System.err.println("URL: " + urlToDownload);

            if (disableHostedDownload && urlToDownload.startsWith(ForgeConstants.URL_CARDFORGE)) {
                System.err.println("Skipping cardforge download (disabled)");
                return false;
            }

            String newdespath = urlToDownload.contains(".fullborder.") || urlToDownload.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD) ?
                    TextUtil.fastReplace(destPath, ".full.", ".fullborder.") : destPath;
            if (!newdespath.contains(".full") && urlToDownload.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD) &&
                    !destPath.startsWith(ForgeConstants.CACHE_TOKEN_PICS_DIR) && !destPath.startsWith(ForgeConstants.CACHE_PLANECHASE_PICS_DIR))
                newdespath = newdespath.replace(".jpg", ".fullborder.jpg");

            System.err.println("Destination (absolute): " + newdespath);

            // iOS fix: Convert to relative path for libGDX compatibility
            String relativePath = toRelativePath(newdespath);
            System.err.println("Destination (relative): " + relativePath);

            FileHandle destFile = Gdx.files.local(relativePath + ".tmp");
            System.err.println("Temp file: " + destFile.file().getAbsolutePath());

            destFile.parent().mkdirs();
            System.err.println("Parent directory ready");

            // Use libGDX Net API for platform-independent HTTP
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Exception> error = new AtomicReference<>();
            final AtomicReference<InputStream> inputStream = new AtomicReference<>();

            Net.HttpRequest request = new Net.HttpRequest(Net.HttpMethods.GET);
            request.setUrl(urlToDownload);
            request.setHeader("User-Agent", BuildInfo.getUserAgent());
            request.setTimeOut(30000); // 30 second timeout

            System.err.println("Sending libGDX HTTP request...");

            Gdx.net.sendHttpRequest(request, new Net.HttpResponseListener() {
                @Override
                public void handleHttpResponse(Net.HttpResponse httpResponse) {
                    try {
                        HttpStatus status = httpResponse.getStatus();
                        System.err.println("HTTP Status: " + status.getStatusCode());

                        if (status.getStatusCode() != 200) {
                            error.set(new IOException("HTTP " + status.getStatusCode()));
                            return;
                        }

                        inputStream.set(httpResponse.getResultAsStream());
                        System.err.println("Got input stream from response");
                    } catch (Exception e) {
                        System.err.println("Error in handleHttpResponse: " + e.getMessage());
                        e.printStackTrace(System.err);
                        error.set(e);
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void failed(Throwable t) {
                    System.err.println("libGDX HTTP request failed: " + t.getMessage());
                    t.printStackTrace(System.err);
                    error.set(new IOException("Request failed", t));
                    latch.countDown();
                }

                @Override
                public void cancelled() {
                    System.err.println("libGDX HTTP request cancelled");
                    error.set(new IOException("Request cancelled"));
                    latch.countDown();
                }
            });

            // Wait for response (max 35 seconds)
            try {
                if (!latch.await(35, TimeUnit.SECONDS)) {
                    System.err.println("Request timeout!");
                    throw new IOException("Request timeout");
                }
            } catch (InterruptedException e) {
                System.err.println("Request interrupted!");
                throw new IOException("Request interrupted", e);
            }

            // Check for errors
            if (error.get() != null) {
                throw new IOException("Download failed", error.get());
            }

            InputStream is = inputStream.get();
            if (is == null) {
                throw new IOException("No input stream received");
            }

            System.err.println("Writing to file...");
            final long[] bytesWritten = {0};
            try (OutputStream out = new FileOutputStream(destFile.file())) {
                // Track bytes
                OutputStream debugOut = new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        out.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        out.write(b, off, len);
                        bytesWritten[0] += len;
                    }
                };

                Forge.getDeviceAdapter().convertToJPEG(is, debugOut);
                is.close();
                System.err.println("Bytes written: " + bytesWritten[0]);
            }

            System.err.println("Temp file size: " + destFile.length());

            FileHandle finalFile = Gdx.files.local(relativePath);
            destFile.moveTo(finalFile);

            System.err.println("Final file: " + finalFile.file().getAbsolutePath());
            System.err.println("Final size: " + finalFile.length() + " bytes");
            System.err.println("libGDX download successful!");
            System.err.println("=== End libGDX Download ===");

            GuiBase.getInterface().invokeInEdtLater(notifyObservers);
            return true;
        }

        private String tofullBorder(String imageurl) {
            if (!imageurl.contains(".full.jpg"))
                return imageurl;
            try {
                URL url = new URL(imageurl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                //connection.setConnectTimeout(1000 * 5); //wait 5 seconds the most
                //connection.setReadTimeout(1000 * 5);
                conn.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
                if(conn.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND)
                    imageurl = TextUtil.fastReplace(imageurl, ".full.jpg", ".fullborder.jpg");
                conn.disconnect();
                return imageurl;
            } catch (IOException ex) {
                return imageurl;
            }
        }

        public void run() {
            System.err.println("=== Download Task Starting ===");
            System.err.println("Thread: " + Thread.currentThread().getName());
            System.err.println("Destination: " + destPath);
            System.err.println("URL count: " + downloadUrls.length);

            boolean success = false;
            for (int i = 0; i < downloadUrls.length; i++) {
                String urlToDownload = downloadUrls[i];
                System.err.println("\n--- Trying URL " + (i + 1) + "/" + downloadUrls.length + " ---");
                System.err.println("URL: " + urlToDownload);

                boolean isPlanechaseBG = urlToDownload.startsWith("PLANECHASEBG:");
                String cleanUrl = urlToDownload.replace("PLANECHASEBG:", "");
                Exception lastError = null;

                try {
                    // iOS: Use URLConnection directly (libGDX Net doesn't work reliably on iOS)
                    System.err.println("Attempting download with URLConnection...");
                    success = doFetch(cleanUrl);

                    if (success) {
                        System.err.println("Download successful with URLConnection!");
                        break;
                    } else {
                        System.err.println("URLConnection returned false (no exception)");
                    }
                } catch (Exception e) {
                    System.err.println("URLConnection download failed!");
                    System.err.println("Error: " + e.getMessage());
                    e.printStackTrace(System.err);
                    lastError = e;
                }

                // If we get here, both methods failed
                if (!success) {
                    System.err.println("!!! Both download methods FAILED !!!");
                    System.err.println("Final error details:");
                    if (lastError != null) {
                        lastError.printStackTrace(System.err);
                    }

                    if (isPlanechaseBG) {
                        System.err.println("Failed to download planechase background [" + destPath + "]");
                    } else {
                        System.err.println("Failed to download card [" + destPath + "]");

                        // Try setless token download
                        if (urlToDownload.contains("tokens")) {
                            System.err.println("Attempting setless token download...");
                            int setIndex = urlToDownload.lastIndexOf('_');
                            int typeIndex = urlToDownload.lastIndexOf('.');
                            String setlessFilename = urlToDownload.substring(0, setIndex);
                            String extension = urlToDownload.substring(typeIndex);
                            urlToDownload = setlessFilename + extension;
                            System.err.println("Setless URL: " + urlToDownload);

                            try {
                                // Try libGDX first, then fallback
                                try {
                                    success = doFetchWithGdxNet(urlToDownload);
                                } catch (Exception ex) {
                                    System.err.println("Setless libGDX failed, trying URLConnection...");
                                    success = doFetch(urlToDownload);
                                }

                                if (success) {
                                    System.err.println("Setless token download successful!");
                                    break;
                                }
                            } catch (Exception t) {
                                System.err.println("Setless token download also failed:");
                                System.err.println("Error: " + t.getMessage());
                                t.printStackTrace(System.err);
                            }
                        }
                    }
                }
            }

            if (!success) {
                System.err.println("\n=== ALL DOWNLOAD ATTEMPTS FAILED ===");
                System.err.println("Destination: " + destPath);
                System.err.println("Tried " + downloadUrls.length + " URL(s)");
            }
            System.err.println("=== Download Task Complete ===\n");
        }
    }

}
