package top.zainzh.rearscreen;

import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.Proxy;

/** A display owned by the existing privileged task backend, not a screen capture. */
final class RearTrustedVirtualDisplay implements AutoCloseable {
    static final int DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    static final int TRUSTED_NON_MIRRORING = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | (1 << 10) | (1 << 11);
    private static final String CALLBACK = "android.hardware.display.IVirtualDisplayCallback";

    private final Class<?> iface = Class.forName("android.hardware.display.IDisplayManager");
    private final Class<?> callbackType = Class.forName(CALLBACK);
    private final Object manager = RearSystemBridge.service("display", iface.getName() + "$Stub");
    private final Object callback;
    private boolean open;

    RearTrustedVirtualDisplay() throws Exception {
        Binder token = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == INTERFACE_TRANSACTION && reply != null) {
                    reply.writeString(CALLBACK);
                    return true;
                }
                if (code >= 1 && code <= 3) {
                    data.enforceInterface(CALLBACK);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "asBinder": return token;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "toString": return "ReMRSS display callback";
                        default: return null;
                    }
                });
    }

    static Object builder(String name, int width, int height, int density, int flags, Surface surface)
            throws Exception {
        Class<?> type = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
        Object builder = type.getConstructor(String.class, int.class, int.class, int.class)
                .newInstance(name, width, height, density);
        type.getMethod("setFlags", int.class).invoke(builder, flags);
        type.getMethod("setSurface", Surface.class).invoke(builder, surface);
        return builder;
    }

    int create(Object config) throws Exception {
        if (open) throw new IllegalStateException("Display already open");
        int id = (Integer) iface.getMethod("createVirtualDisplay", config.getClass(), callbackType,
                Class.forName("android.media.projection.IMediaProjection"), String.class)
                .invoke(manager, config, callback, null, "com.android.shell");
        open = id > 0;
        if (!open) throw new IOException("Virtual display creation refused");
        return id;
    }

    void setSurface(Surface surface) throws Exception {
        iface.getMethod("setVirtualDisplaySurface", callbackType, Surface.class)
                .invoke(manager, callback, surface);
    }

    void resize(int width, int height, int density) throws Exception {
        iface.getMethod("resizeVirtualDisplay", callbackType, int.class, int.class, int.class)
                .invoke(manager, callback, width, height, density);
    }

    @Override public void close() throws Exception {
        if (!open) return;
        iface.getMethod("releaseVirtualDisplay", callbackType).invoke(manager, callback);
        open = false;
    }
}
