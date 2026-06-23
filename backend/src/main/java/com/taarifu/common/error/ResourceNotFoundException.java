package com.taarifu.common.error;

/**
 * Thrown when a requested resource cannot be found by its public id (or is soft-deleted / outside
 * the caller's permitted view) — maps to {@link ErrorCode#NOT_FOUND} → HTTP 404
 * (ARCHITECTURE.md §5.2).
 *
 * <p>Responsibility: an intent-revealing subclass of {@link ApiException} so services express
 * "missing resource" precisely (e.g. {@code GeographyQueryService.getRegion}). The message is
 * localised from the supplied i18n key.</p>
 *
 * <p>WHY a dedicated key per resource (e.g. {@code geography.region.notFound}): gives a Swahili-first
 * message specific to the resource ("Mkoa haukupatikana") rather than a generic "not found"
 * (ADR-0010). The thrown args carry the offending public id for the message template.</p>
 */
public class ResourceNotFoundException extends ApiException {

    /**
     * @param messageKey resource-specific i18n key (e.g. {@code geography.region.notFound}); used as
     *                   the first message argument so the resolver can prefer a resource-specific
     *                   message while still mapping to the generic {@code NOT_FOUND} status/code.
     * @param args       positional arguments (typically the missing public id).
     */
    public ResourceNotFoundException(String messageKey, Object... args) {
        super(ErrorCode.NOT_FOUND, prepend(messageKey, args));
    }

    /** Prepends the resource-specific key as arg[0] for the resolver to optionally use. */
    private static Object[] prepend(String messageKey, Object[] args) {
        Object[] all = new Object[args.length + 1];
        all[0] = messageKey;
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }
}
