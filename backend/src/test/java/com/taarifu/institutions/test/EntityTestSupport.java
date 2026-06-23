package com.taarifu.institutions.test;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Test-only reflective support for building/seeding entities whose production API is deliberately
 * read-only (no public setters for {@code id}/{@code publicId}; protected constructors).
 *
 * <p>Responsibility: lets unit tests assign the internal {@code id} and {@code publicId} that the DB/JPA
 * would otherwise generate, and set arbitrary fields on cross-module geography entities (which expose no
 * mutators) — without weakening the production model. This mirrors how the auth tests reflectively set
 * {@code publicId} on {@code BaseEntity} (see {@code TokenServiceTest}). Tests only.</p>
 */
public final class EntityTestSupport {

    private EntityTestSupport() {
    }

    /**
     * Instantiates an entity via its (possibly protected) no-arg constructor and assigns id/publicId.
     *
     * @param type     the entity class.
     * @param id       the internal {@code Long} id to assign.
     * @param publicId the {@code UUID} public id to assign.
     * @param <T>      the entity type.
     * @return the constructed, id-seeded instance.
     */
    public static <T> T newWithIds(Class<T> type, Long id, UUID publicId) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            T instance = ctor.newInstance();
            set(instance, "id", id);
            set(instance, "publicId", publicId);
            return instance;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not construct " + type.getSimpleName(), ex);
        }
    }

    /**
     * Sets a declared field (walking up to {@code BaseEntity}) on any entity.
     *
     * @param target the entity.
     * @param field  the field name.
     * @param value  the value to assign.
     */
    public static void set(Object target, String field, Object value) {
        try {
            Field f = findField(target.getClass(), field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not set field " + field, ex);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
