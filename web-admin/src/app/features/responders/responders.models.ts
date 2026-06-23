/**
 * Responder directory DTOs — mirror the backend `OrganisationDto`, `ResponderDto`, `RoutingRuleDto` and
 * their write requests (responders module; PRD §24, D20/D21). These shape the organisation + responder
 * admin CRUD/verification and the routing-rules view.
 */

/** Organisation type tokens (mirror the backend enum; validated server-side). */
export const ORGANISATION_TYPES = ['GOVERNMENT', 'PARASTATAL', 'PRIVATE', 'NGO'] as const;

/** Organisation status tokens. */
export const ORGANISATION_STATUSES = ['PENDING', 'ACTIVE', 'SUSPENDED', 'DISABLED'] as const;

/** Responder type tokens. */
export const RESPONDER_TYPES = ['GOVERNMENT', 'PARASTATAL', 'PRIVATE', 'REPRESENTATIVE'] as const;

/** Responder status tokens. */
export const RESPONDER_STATUSES = ['PENDING', 'ACTIVE', 'SUSPENDED', 'DISABLED'] as const;

/** Responder coverage tokens (how a responder's areas are scoped). */
export const COVERAGE_TYPES = ['NATIONAL', 'REGION', 'DISTRICT', 'COUNCIL', 'WARD', 'CUSTOM'] as const;

/** A responding organisation (agency/parastatal/private/NGO). `GET /responders/admin/organisations`. */
export interface Organisation {
  /** The organisation's public id (UUID). */
  id: string;
  /** Display name. */
  name: string;
  /** Type token. */
  type: string;
  /** Status token. */
  status: string;
  /** Whether the organisation is verified (the §24.4 go-live gate). */
  verified: boolean;
  /** Contact phone, or `null`. */
  contactPhone: string | null;
  /** Contact email, or `null`. */
  contactEmail: string | null;
  /** Website URL, or `null`. */
  websiteUrl: string | null;
}

/** A responder capability under an organisation. `GET .../organisations/{id}/responders`. */
export interface Responder {
  /** The responder's public id (UUID). */
  id: string;
  /** Owning organisation public id. */
  organisationId: string;
  /** Owning organisation display name. */
  organisationName: string | null;
  /** Display name. */
  name: string;
  /** Responder type token. */
  responderType: string;
  /** Status token. */
  status: string;
  /** Coverage scoping token. */
  coverageType: string;
  /** Handled reporting-category ids. */
  handledCategoryIds: string[];
  /** Coverage area ids (for non-national coverage). */
  coverageAreaIds: string[];
  /** Free-text SLA policy, or `null`. */
  slaPolicy: string | null;
}

/** A routing rule (category → responder kind/sector). `GET /responders/admin/routing-rules`. */
export interface RoutingRule {
  /** The rule's public id (UUID). */
  id: string;
  /** Reporting-category public id. */
  categoryPublicId: string;
  /** Optional sub-category public id, or `null`. */
  subCategoryPublicId: string | null;
  /** Responder type token. */
  responderType: string;
  /** Provider selection-mode token. */
  selectionMode: string;
  /** Preferred responder public id, or `null`. */
  preferredResponderId: string | null;
  /** Rule priority (lower wins). */
  priority: number;
  /** Whether the rule is active. */
  active: boolean;
}

/** Create body for `POST /responders/admin/organisations`. */
export interface CreateOrganisation {
  /** Display name. */
  name: string;
  /** Type token. */
  type: string;
  /** Optional contact phone. */
  contactPhone?: string;
  /** Optional contact email. */
  contactEmail?: string;
  /** Optional website URL. */
  websiteUrl?: string;
}

/** Update body for `PUT /responders/admin/organisations/{id}` (status included). */
export interface UpdateOrganisation {
  /** Display name. */
  name: string;
  /** Type token. */
  type: string;
  /** Status token. */
  status: string;
  /** Optional contact phone. */
  contactPhone?: string;
  /** Optional contact email. */
  contactEmail?: string;
  /** Optional website URL. */
  websiteUrl?: string;
}

/** Create body for `POST .../organisations/{id}/responders`. */
export interface CreateResponder {
  /** Display name. */
  name: string;
  /** Responder type token. */
  responderType: string;
  /** Coverage scoping token. */
  coverageType: string;
  /** Handled reporting-category ids. */
  handledCategoryIds?: string[];
  /** Coverage area ids. */
  coverageAreaIds?: string[];
  /** Free-text SLA policy. */
  slaPolicy?: string;
}
