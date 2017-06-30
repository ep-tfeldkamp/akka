/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util;

import java.lang.reflect.Field;

/**
 * INTERNAL API
 */
public final class Unsafe {
    public final static sun.misc.Unsafe instance;

    static {
        try {
            sun.misc.Unsafe found = null;
            for (Field field : sun.misc.Unsafe.class.getDeclaredFields()) {
                if (field.getType() == sun.misc.Unsafe.class) {
                    field.setAccessible(true);
                    found = (sun.misc.Unsafe) field.get(null);
                    break;
                }
            }
            if (found == null) throw new IllegalStateException("Can't find instance of sun.misc.Unsafe");
            else instance = found;
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    public static final int getAndAddInt(sun.misc.Unsafe unsafe, Object instance, long fieldOffset, int summand) {
        int current;
        do {
            current = unsafe.getIntVolatile(instance, fieldOffset);
        } while (!unsafe.compareAndSwapInt(instance, fieldOffset, current, current + summand));

        return current;
    }

    public static final long getAndAddLong(sun.misc.Unsafe unsafe, Object instance, long fieldOffset, long summand) {
        long current;
        do {
            current = unsafe.getLongVolatile(instance, fieldOffset);
        } while (!unsafe.compareAndSwapLong(instance, fieldOffset, current, current + summand));

        return current;
    }

    public static final int getAndSetInt(sun.misc.Unsafe unsafe, Object instance, long fieldOffset, int value) {
        int current;
        do {
            current = unsafe.getIntVolatile(instance, fieldOffset);
        } while (!unsafe.compareAndSwapInt(instance, fieldOffset, current, value));

        return current;
    }

    public static final long getAndSetLong(sun.misc.Unsafe unsafe, Object instance, long fieldOffset, long value) {
        long current;
        do {
            current = unsafe.getLongVolatile(instance, fieldOffset);
        } while (!unsafe.compareAndSwapLong(instance, fieldOffset, current, value));

        return current;
    }

    public static final Object getAndSetObject(sun.misc.Unsafe unsafe, Object instance, long fieldOffset, Object value) {
        Object current;
        do {
            current = unsafe.getObjectVolatile(instance, fieldOffset);
        } while (!unsafe.compareAndSwapObject(instance, fieldOffset, current, value));

        return current;
    }
}
