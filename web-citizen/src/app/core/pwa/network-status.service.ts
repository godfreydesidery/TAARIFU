import { Injectable, NgZone, computed, inject, signal } from '@angular/core';

/**
 * Reactive online/offline status for the citizen PWA.
 *
 * <p>Responsibility: a single source of connectivity truth, exposed as a signal so any component (the
 * offline banner, the draft-sync trigger) reacts to the network coming and going. It listens to the
 * browser `online`/`offline` events and, where available, the Network Information API to surface a coarse
 * `network_type` (2g/3g/4g/wifi) and a `low_data_mode` hint (the `saveData` flag). These feed the analytics
 * standard dimensions and the low-data UX (PRD §15) without ever blocking the citizen path.</p>
 *
 * <p>WHY events are bridged through {@link NgZone}: the native listeners fire outside Angular's zone, so we
 * re-enter the zone to keep signal updates in change-detection.</p>
 */
@Injectable({ providedIn: 'root' })
export class NetworkStatusService {
  private readonly zone = inject(NgZone);

  /** Reactive flag: true when the browser believes it is online. */
  private readonly onlineSignal = signal<boolean>(navigator.onLine);

  /** Read-only online flag. */
  readonly online = this.onlineSignal.asReadonly();

  /** Read-only offline flag (negation of {@link online}) — convenient for templates. */
  readonly offline = computed(() => !this.onlineSignal());

  /** Coarse connection type for analytics dims (`network_type`); `unknown` when unsupported. */
  private readonly networkTypeSignal = signal<string>(this.readNetworkType());

  /** Read-only coarse connection type. */
  readonly networkType = this.networkTypeSignal.asReadonly();

  /** Read-only low-data hint from the platform `saveData` preference. */
  readonly lowDataMode = signal<boolean>(this.readSaveData());

  constructor() {
    window.addEventListener('online', () => this.zone.run(() => this.onlineSignal.set(true)));
    window.addEventListener('offline', () => this.zone.run(() => this.onlineSignal.set(false)));
    const conn = this.connection();
    if (conn && typeof conn.addEventListener === 'function') {
      conn.addEventListener('change', () =>
        this.zone.run(() => {
          this.networkTypeSignal.set(this.readNetworkType());
          this.lowDataMode.set(this.readSaveData());
        }),
      );
    }
  }

  /** @returns the platform `NetworkInformation`, or `undefined` when the API is unavailable. */
  private connection(): NetworkInformationLike | undefined {
    const nav = navigator as NavigatorWithConnection;
    return nav.connection ?? nav.mozConnection ?? nav.webkitConnection;
  }

  /** Reads the effective connection type (e.g. `4g`), or `unknown`. */
  private readNetworkType(): string {
    return this.connection()?.effectiveType ?? 'unknown';
  }

  /** Reads the `saveData` (Data Saver) preference, defaulting to false. */
  private readSaveData(): boolean {
    return this.connection()?.saveData ?? false;
  }
}

/** Minimal structural type for the Network Information API (not in the TS DOM lib by default). */
interface NetworkInformationLike {
  effectiveType?: string;
  saveData?: boolean;
  addEventListener?: (type: 'change', listener: () => void) => void;
}

/** Navigator augmented with the vendor-prefixed connection properties. */
interface NavigatorWithConnection extends Navigator {
  connection?: NetworkInformationLike;
  mozConnection?: NetworkInformationLike;
  webkitConnection?: NetworkInformationLike;
}
