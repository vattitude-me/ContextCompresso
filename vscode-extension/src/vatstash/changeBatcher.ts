/**
 * Coalesces file-watcher events into debounced batches. Deliberately free of any `vscode`
 * import so it can be unit-tested with node:test.
 */

export type FlushFn = (changed: string[], deleted: string[]) => Promise<void>;

const IGNORED_SEGMENTS = ['/node_modules/', '/.git/', '/target/', '/out/', '/dist/', '/build/'];

/**
 * Cheap client-side filter so a build or `npm install` does not flood the proxy with events it
 * will discard anyway. The Java side applies the real ignore rules (.gitignore and friends).
 */
export function isObviouslyIgnored(path: string): boolean {
    const normalized = path.replace(/\\/g, '/');
    return IGNORED_SEGMENTS.some((segment) => normalized.includes(segment));
}

export class ChangeBatcher {
    // Insertion-ordered; re-setting a key keeps its slot but the latest kind wins.
    private readonly pending = new Map<string, 'changed' | 'deleted'>();
    private timer: ReturnType<typeof setTimeout> | null = null;
    private chain: Promise<void> = Promise.resolve();
    private disposed = false;

    constructor(
        private readonly flush: FlushFn,
        private readonly debounceMs = 1500,
        private readonly maxBatch = 500
    ) {}

    changed(path: string): void {
        this.add(path, 'changed');
    }

    deleted(path: string): void {
        this.add(path, 'deleted');
    }

    /** Resolves once everything queued so far has been handed to `flush`. For tests. */
    idle(): Promise<void> {
        return this.chain;
    }

    dispose(): void {
        this.disposed = true;
        this.pending.clear();
        if (this.timer) {
            clearTimeout(this.timer);
            this.timer = null;
        }
    }

    private add(path: string, kind: 'changed' | 'deleted'): void {
        if (this.disposed) {
            return;
        }
        this.pending.set(path, kind);
        if (this.pending.size >= this.maxBatch) {
            this.schedule(0); // a burst this large (a branch switch) should not wait out the debounce
        } else {
            this.schedule(this.debounceMs);
        }
    }

    private schedule(delayMs: number): void {
        if (this.timer) {
            clearTimeout(this.timer);
        }
        this.timer = setTimeout(() => {
            this.timer = null;
            this.drain();
        }, delayMs);
    }

    private drain(): void {
        while (this.pending.size > 0) {
            const changed: string[] = [];
            const deleted: string[] = [];
            let taken = 0;
            for (const [path, kind] of this.pending) {
                if (taken >= this.maxBatch) {
                    break;
                }
                (kind === 'changed' ? changed : deleted).push(path);
                this.pending.delete(path);
                taken++;
            }
            // Flushes run strictly in order so a delete can never overtake an earlier create.
            this.chain = this.chain
                .then(() => this.flush(changed, deleted))
                .catch(() => { /* the flush callback owns its error handling; never break the chain */ });
        }
    }
}
