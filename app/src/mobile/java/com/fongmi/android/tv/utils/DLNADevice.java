package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.Device;

import org.jupnp.model.meta.RemoteDevice;
import java.util.LinkedHashSet;
import java.util.Set;

public class DLNADevice {

    private final Set<RemoteDevice> devices;

    private static class Loader {
        static final DLNADevice INSTANCE = new DLNADevice();
    }

    public static DLNADevice get() {
        return Loader.INSTANCE;
    }

    private DLNADevice() {
        this.devices = new LinkedHashSet<>();
    }

    // 添加设备
    public Device add(RemoteDevice item) {
        devices.add(item);
        return Device.get(item);
    }

    // 移除设备
    public Device remove(RemoteDevice item) {
        devices.remove(item);
        return Device.get(item);
    }

    // 断开所有设备（纯 jUPnP，不再使用 DLNACastManager）
    public void disconnect() {
        devices.clear();
    }

    // 查找设备
    public RemoteDevice find(Device item) {
        return devices.stream()
                .filter(d -> d.getIdentity().getUdn().getIdentifierString().equals(item.getUuid()))
                .findFirst()
                .orElse(null);
    }
}