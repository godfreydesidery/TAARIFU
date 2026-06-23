package com.taarifu.common.pagination;

import com.taarifu.common.api.dto.PageMeta;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * Translates a Spring Data {@link Page} into the {@link PageMeta} carried by the envelope
 * (PRD §17, ARCHITECTURE.md §5.3).
 *
 * <p>Responsibility: the single adapter from Spring's pagination model to Taarifu's wire contract,
 * so the {@code meta} block is identical on every paged endpoint (DRY). The page <i>content</i> is
 * mapped to DTOs by each module's mapper; this class produces only the metadata.</p>
 */
@Component
public class PageMapper {

    /**
     * Extracts {@link PageMeta} from a page.
     *
     * @param page any Spring Data {@link Page} (its element type is irrelevant to the metadata).
     * @return the wire-contract pagination metadata.
     */
    public PageMeta toMeta(Page<?> page) {
        return new PageMeta(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
