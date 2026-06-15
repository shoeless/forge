package io.sentry;
import io.sentry.protocol.Device;
import io.sentry.protocol.OperatingSystem;
/** No-op stub for the RoboVM/iOS build (real io.sentry SDK is excluded). */
public class Contexts {
    public void setDevice(Device device) { }
    public void setOperatingSystem(OperatingSystem operatingSystem) { }
}
