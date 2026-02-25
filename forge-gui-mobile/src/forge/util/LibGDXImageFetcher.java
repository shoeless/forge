package forge.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

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
         * On iOS, files are in Documents/ but libGDX local base is Library/local/,
         * so we need to construct a relative path like ../../Documents/...
         */
        private String toRelativePath(String absolutePath) {
            int documentsIndex = absolutePath.indexOf("/Documents/");
            if (documentsIndex != -1) {
                return "../../Documents" + absolutePath.substring(documentsIndex + "/Documents".length());
            }
            int libraryIndex = absolutePath.indexOf("/Library/");
            if (libraryIndex != -1) {
                return absolutePath.substring(absolutePath.indexOf("/Library/") + "/Library/".length());
            }
            return absolutePath;
        }

        /**
         * Resolve a Scryfall API image URL to a direct CDN URL.
         * RoboVM's OkHttp cannot follow Scryfall's 302 redirects properly,
         * so we query the JSON API to get the direct image URL instead.
         *
         * Input:  https://api.scryfall.com/cards/m3c/160?format=image&version=normal
         * Output: https://cards.scryfall.io/normal/front/.../uuid.jpg
         */
        private String resolveScryfallUrl(String scryfallUrl) {
            // Extract the JSON API URL by removing ?format=image... parameters
            int queryIndex = scryfallUrl.indexOf('?');
            if (queryIndex == -1) return scryfallUrl;

            String jsonUrl = scryfallUrl.substring(0, queryIndex);
            boolean useArtCrop = scryfallUrl.contains("version=art_crop");
            String face = "";
            if (scryfallUrl.contains("face=back")) face = "back";

            try {
                URL url = new URL(jsonUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                int code = conn.getResponseCode();
                if (code != 200) {
                    System.err.println("  Scryfall JSON API returned " + code + " for " + jsonUrl);
                    conn.disconnect();
                    return null;
                }

                // Read JSON response
                StringBuilder sb = new StringBuilder();
                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
                reader.close();
                conn.disconnect();

                String json = sb.toString();

                // For back face, look in card_faces array
                if ("back".equals(face)) {
                    String cdnUrl = extractImageUrl(json, useArtCrop, 1);
                    if (cdnUrl != null) return cdnUrl;
                }

                // Look in top-level image_uris
                return extractImageUrl(json, useArtCrop, 0);
            } catch (Exception e) {
                System.err.println("  Scryfall JSON resolve failed: " + e.getMessage());
                return null;
            }
        }

        /**
         * Extract image URL from Scryfall JSON response.
         * @param faceIndex 0 = front/top-level, 1 = back face
         */
        private String extractImageUrl(String json, boolean useArtCrop, int faceIndex) {
            String searchBlock = json;

            if (faceIndex > 0) {
                // Find the card_faces array and skip to the right face
                int facesStart = json.indexOf("\"card_faces\"");
                if (facesStart == -1) return null;
                int currentFace = 0;
                int pos = facesStart;
                while (currentFace < faceIndex) {
                    pos = json.indexOf("\"image_uris\"", pos + 1);
                    if (pos == -1) return null;
                    currentFace++;
                }
                searchBlock = json.substring(pos);
            }

            String key = useArtCrop ? "\"art_crop\":\"" : "\"normal\":\"";
            int start = searchBlock.indexOf(key);
            if (start == -1) {
                // Fallback to normal if art_crop not found
                if (useArtCrop) {
                    key = "\"normal\":\"";
                    start = searchBlock.indexOf(key);
                }
                if (start == -1) return null;
            }
            start += key.length();
            int end = searchBlock.indexOf("\"", start);
            if (end == -1) return null;

            return searchBlock.substring(start, end);
        }

        private HttpURLConnection openConnection(String urlString) throws IOException {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            return conn;
        }

        private boolean doFetch(String urlToDownload) throws IOException {
            System.err.println("=== Download: " + urlToDownload);

            if (disableHostedDownload && urlToDownload.startsWith(ForgeConstants.URL_CARDFORGE)) {
                System.err.println("  Skipping cardforge (disabled)");
                return false;
            }

            String newdespath = urlToDownload.contains(".fullborder.") || urlToDownload.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD) ?
                    TextUtil.fastReplace(destPath, ".full.", ".fullborder.") : destPath;
            if (!newdespath.contains(".full") && urlToDownload.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD) &&
                    !destPath.startsWith(ForgeConstants.CACHE_TOKEN_PICS_DIR) && !destPath.startsWith(ForgeConstants.CACHE_PLANECHASE_PICS_DIR))
                newdespath = newdespath.replace(".jpg", ".fullborder.jpg");

            String relativePath = toRelativePath(newdespath);
            FileHandle destFile = Gdx.files.local(relativePath + ".tmp");
            destFile.parent().mkdirs();

            // For Scryfall API URLs, resolve to direct CDN URL first
            // (RoboVM's OkHttp can't follow Scryfall's 302 redirects)
            String downloadUrl = urlToDownload;
            if (urlToDownload.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD)) {
                String cdnUrl = resolveScryfallUrl(urlToDownload);
                if (cdnUrl == null) {
                    System.err.println("  Could not resolve Scryfall CDN URL");
                    return false;
                }
                System.err.println("  Resolved -> " + cdnUrl);
                downloadUrl = cdnUrl;
            }

            HttpURLConnection conn = openConnection(downloadUrl);
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                System.err.println("  HTTP " + responseCode + " from " + downloadUrl);
                conn.disconnect();
                return false;
            }

            InputStream is = conn.getInputStream();
            final long[] bytesWritten = {0};
            try (OutputStream out = new FileOutputStream(destFile.file())) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    out.write(buf, 0, len);
                    bytesWritten[0] += len;
                }
            } finally {
                is.close();
                conn.disconnect();
            }

            if (bytesWritten[0] == 0) {
                System.err.println("  Downloaded 0 bytes, skipping");
                destFile.delete();
                return false;
            }

            FileHandle finalFile = Gdx.files.local(relativePath);
            destFile.moveTo(finalFile);

            System.err.println("  OK " + bytesWritten[0] + " bytes -> " + finalFile.file().getAbsolutePath());

            GuiBase.getInterface().invokeInEdtLater(notifyObservers);
            return true;
        }

        public void run() {
            boolean success = false;
            for (int i = 0; i < downloadUrls.length; i++) {
                String urlToDownload = downloadUrls[i];
                boolean isPlanechaseBG = urlToDownload.startsWith("PLANECHASEBG:");
                String cleanUrl = urlToDownload.replace("PLANECHASEBG:", "");

                try {
                    success = doFetch(cleanUrl);
                    if (success) break;
                } catch (Exception e) {
                    System.err.println("  FAILED: " + e.getMessage());
                }

                // Token setless fallback
                if (!success && !isPlanechaseBG && urlToDownload.contains("tokens")) {
                    int setIndex = urlToDownload.lastIndexOf('_');
                    int typeIndex = urlToDownload.lastIndexOf('.');
                    if (setIndex > 0 && typeIndex > setIndex) {
                        String setlessUrl = urlToDownload.substring(0, setIndex) + urlToDownload.substring(typeIndex);
                        try {
                            success = doFetch(setlessUrl);
                            if (success) break;
                        } catch (Exception e) {
                            System.err.println("  Setless token FAILED: " + e.getMessage());
                        }
                    }
                }
            }

            if (!success) {
                System.err.println("All " + downloadUrls.length + " URLs failed for: " + destPath);
            }
        }
    }

}
