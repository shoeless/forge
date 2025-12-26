package forge.util;

import io.sentry.protocol.Device;
import io.sentry.protocol.OperatingSystem;

public class HWInfo {
    private final Device device;
    private final OperatingSystem os;
    private final boolean getChipset;

    public HWInfo(Device device, OperatingSystem os, boolean getChipset) {
        this.device = device;
        this.os = os;
        this.getChipset = getChipset;
    }

    public Device device() {
        return device;
    }

    public OperatingSystem os() {
        return os;
    }

    public boolean getChipset() {
        return getChipset;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        HWInfo other = (HWInfo) obj;
        return getChipset == other.getChipset &&
               java.util.Objects.equals(device, other.device) &&
               java.util.Objects.equals(os, other.os);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(device, os, getChipset);
    }

    @Override
    public String toString() {
        return "HWInfo[device=" + device + ", os=" + os + ", getChipset=" + getChipset + "]";
    }
}
