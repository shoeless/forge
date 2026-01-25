package forge.ios;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * A PrintStream that redirects output to os_log for iOS 26+ compatibility.
 * Use this to redirect System.out and System.err so all logging is visible.
 */
public class OSLogPrintStream extends PrintStream {
    private final String prefix;
    private final boolean isError;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    public OSLogPrintStream(String prefix, boolean isError) {
        super(new OutputStream() {
            @Override
            public void write(int b) {
                // Dummy - we override the print methods
            }
        });
        this.prefix = prefix;
        this.isError = isError;
    }

    @Override
    public void println(String x) {
        log(x);
    }

    @Override
    public void println(Object x) {
        log(String.valueOf(x));
    }

    @Override
    public void println() {
        log("");
    }

    @Override
    public void println(boolean x) {
        log(String.valueOf(x));
    }

    @Override
    public void println(char x) {
        log(String.valueOf(x));
    }

    @Override
    public void println(int x) {
        log(String.valueOf(x));
    }

    @Override
    public void println(long x) {
        log(String.valueOf(x));
    }

    @Override
    public void println(float x) {
        log(String.valueOf(x));
    }

    @Override
    public void println(double x) {
        log(String.valueOf(x));
    }

    @Override
    public void println(char[] x) {
        log(new String(x));
    }

    @Override
    public void print(String s) {
        if (s == null) s = "null";
        synchronized (buffer) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\n') {
                    flushBuffer();
                } else {
                    buffer.write(c);
                }
            }
        }
    }

    @Override
    public void print(Object obj) {
        print(String.valueOf(obj));
    }

    @Override
    public void print(boolean b) {
        print(String.valueOf(b));
    }

    @Override
    public void print(char c) {
        print(String.valueOf(c));
    }

    @Override
    public void print(int i) {
        print(String.valueOf(i));
    }

    @Override
    public void print(long l) {
        print(String.valueOf(l));
    }

    @Override
    public void print(float f) {
        print(String.valueOf(f));
    }

    @Override
    public void print(double d) {
        print(String.valueOf(d));
    }

    @Override
    public void print(char[] s) {
        print(new String(s));
    }

    @Override
    public void write(int b) {
        synchronized (buffer) {
            if (b == '\n') {
                flushBuffer();
            } else {
                buffer.write(b);
            }
        }
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        synchronized (buffer) {
            for (int i = off; i < off + len; i++) {
                write(buf[i]);
            }
        }
    }

    @Override
    public void flush() {
        synchronized (buffer) {
            if (buffer.size() > 0) {
                flushBuffer();
            }
        }
    }

    private void flushBuffer() {
        String line = buffer.toString();
        buffer.reset();
        log(line);
    }

    private void log(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        String fullMessage = prefix + message;
        try {
            if (isError) {
                ForgeOSLog.logError(fullMessage);
            } else {
                ForgeOSLog.logPublic(fullMessage);
            }
        } catch (Throwable t) {
            // If os_log fails, we can't do much - avoid infinite recursion
        }
    }
}
