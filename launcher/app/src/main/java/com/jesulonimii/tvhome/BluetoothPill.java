package com.jesulonimii.tvhome;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Paired Bluetooth devices for the top bar's Bluetooth pill: which are connected, and connect or disconnect one.
 * Connecting uses the hidden BluetoothA2dp/BluetoothHidHost connect(), which Android 11 still lets apps call.
 */
@SuppressLint("MissingPermission")
final class BluetoothPill {

    private static final String TAG = "TvHome.Bluetooth";
    // BluetoothProfile.HID_HOST, hidden until API 28 docs.
    private static final int HID_HOST = 4;
    private static final String A2DP_CHANGED = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    private static final String HID_CHANGED = "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED";

    /** One paired device, as the dropdown shows it. */
    static final class Device {
        final BluetoothDevice device;
        final String name;
        final boolean audio;
        final boolean input;
        final boolean connected;

        Device(BluetoothDevice device, String name, boolean audio, boolean input, boolean connected) {
            this.device = device;
            this.name = name;
            this.audio = audio;
            this.input = input;
            this.connected = connected;
        }
    }

    private final Context context;
    private final Runnable onChange;
    private final BluetoothAdapter adapter;
    private BluetoothProfile a2dp;
    private BluetoothProfile hid;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            onChange.run();
        }
    };

    BluetoothPill(Context context, Runnable onChange) {
        this.context = context.getApplicationContext();
        this.onChange = onChange;
        this.adapter = BluetoothAdapter.getDefaultAdapter();
    }

    boolean available() {
        return adapter != null;
    }

    boolean enabled() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Opens the profile proxies and listens for connection changes until stop(). */
    void start() {
        if (adapter == null || registered) return;
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) a2dp = proxy;
                else if (profile == HID_HOST) hid = proxy;
                onChange.run();
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.A2DP) a2dp = null;
                else if (profile == HID_HOST) hid = null;
            }
        };
        try {
            adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP);
            adapter.getProfileProxy(context, listener, HID_HOST);
        } catch (SecurityException e) {
            Log.w(TAG, "no Bluetooth permission", e);
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(A2DP_CHANGED);
        filter.addAction(HID_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        registered = true;
    }

    void stop() {
        if (!registered) return;
        context.unregisterReceiver(receiver);
        registered = false;
        if (a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        if (hid != null) adapter.closeProfileProxy(HID_HOST, hid);
        a2dp = null;
        hid = null;
    }

    /** Paired devices, connected first, then by name. Empty while Bluetooth is off. */
    List<Device> devices() {
        List<Device> list = new ArrayList<>();
        if (!enabled()) return list;
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            return list;
        }
        if (bonded == null) return list;
        Set<BluetoothDevice> connected = new HashSet<>();
        try {
            if (a2dp != null) connected.addAll(a2dp.getConnectedDevices());
            if (hid != null) connected.addAll(hid.getConnectedDevices());
        } catch (SecurityException ignored) {
            // No permission: every device reads as not connected.
        }
        for (BluetoothDevice d : bonded) {
            String name = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? d.getAlias() : null;
            if (name == null || name.isEmpty()) name = d.getName();
            if (name == null || name.isEmpty()) name = d.getAddress();
            BluetoothClass cls = d.getBluetoothClass();
            int major = cls == null ? -1 : cls.getMajorDeviceClass();
            boolean audio = major == BluetoothClass.Device.Major.AUDIO_VIDEO
                    || (cls != null && cls.hasService(BluetoothClass.Service.RENDER));
            boolean input = major == BluetoothClass.Device.Major.PERIPHERAL;
            list.add(new Device(d, name, audio && !input, input || !audio, connected.contains(d)));
        }
        Collections.sort(list, (a, b) -> {
            if (a.connected != b.connected) return a.connected ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return list;
    }

    /** The connected audio device's name, else null: the pill shows it next to its icon. */
    String connectedAudioName() {
        for (Device d : devices()) if (d.connected && d.audio) return d.name;
        return null;
    }

    /** Asks Android to connect the device on its profile; the receiver reports the result. */
    boolean connect(Device d) {
        return call(d.audio ? a2dp : hid, "connect", d.device);
    }

    /** Audio devices only: disconnecting a remote or keyboard here could leave the TV with no way to steer it. */
    boolean disconnect(Device d) {
        return d.audio && call(a2dp, "disconnect", d.device);
    }

    private static boolean call(BluetoothProfile proxy, String method, BluetoothDevice device) {
        if (proxy == null) return false;
        try {
            Method m = proxy.getClass().getMethod(method, BluetoothDevice.class);
            Object result = m.invoke(proxy, device);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (ReflectiveOperationException | SecurityException e) {
            Log.w(TAG, method + " failed", e);
            return false;
        }
    }
}
