package forge.util;

import com.apptasticsoftware.rssreader.Item;
import com.apptasticsoftware.rssreader.RssReader;
import org.apache.commons.text.StringEscapeUtils;

import java.io.InputStream;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class RSSReader {
    public static String getCommitLog(String commitsAtom, Date buildDateOriginal, Date maxDate) {
        String message = "";
        SimpleDateFormat simpleDate = TextUtil.getSimpleDate();
        try {
            RssReader reader = new RssReader();
            URL url = new URL(commitsAtom);
            InputStream inputStream = url.openStream();
            // iOS compatibility: Use traditional loop instead of Stream.forEach() (Java 8 method)
            List<Item> items = new ArrayList<>();
            for (Item item : (Iterable<Item>) () -> reader.read(inputStream).iterator()) {
                items.add(item);
            }
            StringBuilder logs = new StringBuilder();
            int c = 0;
            for (Item i : items) {
                if (i.getTitle().isEmpty())
                    continue;
                String title = TextUtil.stripNonValidXMLCharacters(i.getTitle().get());
                if (title.contains("Merge"))
                    continue;
                // iOS compatibility: Parse pubDate string instead of using ZonedDateTime
                Date feedDate = null;
                if (i.getPubDate().isPresent()) {
                    String pubDateStr = i.getPubDate().get();
                    try {
                        // Try RFC 822 date format (common for RSS feeds)
                        SimpleDateFormat rfc822 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z");
                        feedDate = rfc822.parse(pubDateStr);
                    } catch (ParseException e1) {
                        try {
                            // Try ISO 8601 format as fallback
                            SimpleDateFormat iso8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
                            feedDate = iso8601.parse(pubDateStr.replaceAll("([+-]\\d{2}):(\\d{2})$", "$1$2"));
                        } catch (ParseException e2) {
                            // Skip this item if we can't parse the date
                            continue;
                        }
                    }
                }
                if (feedDate == null)
                    continue;
                if (buildDateOriginal != null && feedDate.before(buildDateOriginal))
                    continue;
                if (maxDate != null && feedDate.after(maxDate))
                    continue;
                logs.append(simpleDate.format(feedDate)).append(" | ").append(StringEscapeUtils.unescapeXml(title).replace("\n", "").replace("        ", "")).append("\n\n");
                if (c >= 15)
                    break;
                c++;
            }
            if (logs.length() > 0)
                message += ("\n\nLatest Changes:\n\n" + logs);
            inputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return message;
    }
    public static String getLatestReleaseTag(String releaseAtom) {
        String tag = "";
        try {
            RssReader reader = new RssReader();
            URL url = new URL(releaseAtom);
            InputStream inputStream = url.openStream();
            // iOS compatibility: Use traditional loop instead of Stream.forEach() (Java 8 method)
            List<Item> items = new ArrayList<>();
            for (Item item : (Iterable<Item>) () -> reader.read(inputStream).iterator()) {
                items.add(item);
            }
            for (Item i : items) {
                if (i.getLink().isPresent()) {
                    try {
                        String val = i.getLink().get();
                        tag = val.substring(val.lastIndexOf("forge"));
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            inputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return tag;
    }
}
