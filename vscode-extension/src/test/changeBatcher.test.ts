import { test } from 'node:test';
import assert from 'node:assert';
import { ChangeBatcher, isObviouslyIgnored } from '../vatstash/changeBatcher';

type Flush = { changed: string[]; deleted: string[] };

function collector(): { calls: Flush[]; flush: (c: string[], d: string[]) => Promise<void> } {
    const calls: Flush[] = [];
    return { calls, flush: async (changed, deleted) => { calls.push({ changed, deleted }); } };
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

test('debouncesBurstIntoOneFlush', async () => {
    const { calls, flush } = collector();
    const batcher = new ChangeBatcher(flush, 30, 500);
    batcher.changed('/w/a.java');
    batcher.changed('/w/b.java');
    batcher.changed('/w/a.java');
    await sleep(10);
    assert.strictEqual(calls.length, 0, 'nothing flushes inside the debounce window');
    await sleep(80);
    await batcher.idle();
    assert.strictEqual(calls.length, 1);
    assert.deepStrictEqual(calls[0].changed, ['/w/a.java', '/w/b.java']);
    assert.deepStrictEqual(calls[0].deleted, []);
    batcher.dispose();
});

test('deleteAfterChangeWins', async () => {
    const { calls, flush } = collector();
    const batcher = new ChangeBatcher(flush, 20, 500);
    batcher.changed('/w/a.ts');
    batcher.deleted('/w/a.ts');
    batcher.deleted('/w/b.ts');
    batcher.changed('/w/b.ts');
    await sleep(60);
    await batcher.idle();
    assert.strictEqual(calls.length, 1);
    assert.deepStrictEqual(calls[0].changed, ['/w/b.ts']);
    assert.deepStrictEqual(calls[0].deleted, ['/w/a.ts']);
    batcher.dispose();
});

test('splitsBatchesAtMax', async () => {
    const { calls, flush } = collector();
    const batcher = new ChangeBatcher(flush, 20, 3);
    for (let i = 0; i < 7; i++) {
        batcher.changed(`/w/f${i}.ts`);
    }
    await sleep(60);
    await batcher.idle();
    assert.ok(calls.length >= 3, `expected at least 3 flushes, got ${calls.length}`);
    assert.ok(calls.every((c) => c.changed.length + c.deleted.length <= 3));
    const all = calls.flatMap((c) => c.changed);
    assert.deepStrictEqual([...all].sort(), Array.from({ length: 7 }, (_, i) => `/w/f${i}.ts`));
    batcher.dispose();
});

test('disposeDropsPendingEvents', async () => {
    const { calls, flush } = collector();
    const batcher = new ChangeBatcher(flush, 20, 500);
    batcher.changed('/w/a.ts');
    batcher.dispose();
    await sleep(50);
    assert.strictEqual(calls.length, 0);
});

test('isObviouslyIgnoredMatchesBuildDirs', () => {
    for (const dir of ['node_modules', '.git', 'target', 'out', 'dist', 'build']) {
        assert.strictEqual(isObviouslyIgnored(`/w/proj/${dir}/x/y.js`), true, dir);
    }
    assert.strictEqual(isObviouslyIgnored('C:\\w\\proj\\node_modules\\x.js'), true);
    assert.strictEqual(isObviouslyIgnored('/w/proj/src/main/App.java'), false);
    assert.strictEqual(isObviouslyIgnored('/w/proj/outbound/App.java'), false);
});
