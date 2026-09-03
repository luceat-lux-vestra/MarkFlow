import {markdown} from "@codemirror/lang-markdown";
import {syntaxTree} from "@codemirror/language";
import {
    Annotation,
    EditorState,
    StateEffect
} from "@codemirror/state";
import type {ChangeSet, Range} from "@codemirror/state";
import {Decoration, EditorView, ViewPlugin} from "@codemirror/view";
import type {ViewUpdate} from "@codemirror/view";

/**
 * A source edit uses the UTF-16 offsets used by JavaScript strings and by the
 * host SourceEdit contract. `from` and `to` refer to the document before the
 * containing CodeMirror transaction. Changes are ordered by their source
 * positions and are not merged across disjoint ranges.
 */
export interface SourceChange {
    readonly from: number;
    readonly to: number;
    readonly inserted: string;
}

export interface SourceChangeTransaction {
    readonly coordinateSpace: "pre-transaction";
    readonly changes: readonly SourceChange[];
}

export interface SourceNativeEditorOptions {
    readonly parent: Element;
    readonly initialSource: string;
    /**
     * Runs after the local transaction has been normalized but before CodeMirror applies it.
     * Returning false rejects the source projection mutation at the dispatch boundary.
     */
    readonly onBeforeLocalChange?: (change: SourceChangeTransaction) => boolean;
    readonly onLocalChange: (change: SourceChangeTransaction) => void;
    /**
     * Optional diagnostic observation of the bounded syntax ranges inspected
     * by the representative preview. This is not part of source correctness
     * or host transport.
     */
    readonly onPreviewRangeScanned?: (range: PreviewScanRange) => void;
}

export interface PreviewScanRange {
    readonly from: number;
    readonly to: number;
}

const hostOrigin = Annotation.define<true>();

const sourceNativeTheme = EditorView.baseTheme({
    ".cm-source-native-emphasis": {
        fontStyle: "italic"
    },
    ".cm-source-native-strong": {
        fontWeight: "bold"
    }
});

const refreshPreview = StateEffect.define<void>();
const PREVIEW_CONTEXT_LINES = 1;

export const SOURCE_NATIVE_MAX_EDITS = 1024;
export const SOURCE_NATIVE_MAX_INSERTED_UTF16 = 4 * 1024 * 1024;

type DecorationSet = ReturnType<typeof Decoration.set>;

function mergeRanges(ranges: readonly PreviewScanRange[]): PreviewScanRange[] {
    const sorted = [...ranges].sort((left, right) => left.from - right.from || left.to - right.to);
    const merged: PreviewScanRange[] = [];

    for (const range of sorted) {
        const previous = merged.at(-1);
        if (previous === undefined || range.from > previous.to) {
            merged.push({from: range.from, to: range.to});
        } else if (range.to > previous.to) {
            merged[merged.length - 1] = {from: previous.from, to: range.to};
        }
    }

    return merged;
}

function expandToPreviewRange(state: EditorState, range: PreviewScanRange): PreviewScanRange {
    const from = Math.max(0, Math.min(range.from, state.doc.length));
    const to = Math.max(from, Math.min(range.to, state.doc.length));
    const firstLine = state.doc.lineAt(from);
    const lastLine = state.doc.lineAt(to);
    const firstLineNumber = Math.max(1, firstLine.number - PREVIEW_CONTEXT_LINES);
    const lastLineNumber = Math.min(state.doc.lines, lastLine.number + PREVIEW_CONTEXT_LINES);

    return {
        from: state.doc.line(firstLineNumber).from,
        to: state.doc.line(lastLineNumber).to
    };
}

function expandToPreviewRanges(state: EditorState, ranges: readonly PreviewScanRange[]): PreviewScanRange[] {
    return mergeRanges(ranges.map((range) => expandToPreviewRange(state, range)));
}

function visiblePreviewRanges(view: EditorView): PreviewScanRange[] {
    return expandToPreviewRanges(view.state, view.visibleRanges);
}

function changedPreviewRanges(state: EditorState, changes: ChangeSet): PreviewScanRange[] {
    const ranges: PreviewScanRange[] = [];
    changes.iterChangedRanges((_fromA, _toA, fromB, toB) => {
        ranges.push({from: fromB, to: toB});
    }, true);
    return expandToPreviewRanges(state, ranges);
}

function overlaps(left: PreviewScanRange, right: PreviewScanRange): boolean {
    return left.from < right.to && right.from < left.to;
}

function previewClassName(nodeName: string): string | undefined {
    return nodeName === "Emphasis"
        ? "cm-source-native-emphasis"
        : nodeName === "StrongEmphasis"
            ? "cm-source-native-strong"
            : undefined;
}

function buildPreviewDecorations(
    state: EditorState,
    scanRanges: readonly PreviewScanRange[],
    onPreviewRangeScanned?: (range: PreviewScanRange) => void
): Range<Decoration>[] {
    const decorations: Range<Decoration>[] = [];

    for (const range of scanRanges) {
        onPreviewRangeScanned?.(range);
        syntaxTree(state).iterate({
            from: range.from,
            to: range.to,
            enter(node) {
                const className = previewClassName(node.name);
                if (className !== undefined && node.from >= range.from && node.to <= range.to) {
                    decorations.push(Decoration.mark({class: className}).range(node.from, node.to));
                }
            }
        });
    }

    return decorations;
}

function removePreviewDecorations(value: DecorationSet, ranges: readonly PreviewScanRange[]): DecorationSet {
    let result = value;
    for (const range of ranges) {
        result = result.update({
            filter: (from, to) => !overlaps({from, to}, range),
            filterFrom: range.from,
            filterTo: range.to
        });
    }
    return result;
}

function retainVisiblePreviewDecorations(
    value: DecorationSet,
    visibleRanges: readonly PreviewScanRange[],
    documentLength: number
): DecorationSet {
    return value.update({
        filter: (from, to) => visibleRanges.some((range) => overlaps({from, to}, range)),
        filterFrom: 0,
        filterTo: documentLength
    });
}

function createPreviewPlugin(
    onPreviewRangeScanned?: (range: PreviewScanRange) => void
) {
    return ViewPlugin.fromClass(class {
        decorations: DecorationSet;

        constructor(view: EditorView) {
            this.decorations = Decoration.set(buildPreviewDecorations(
                view.state,
                visiblePreviewRanges(view),
                onPreviewRangeScanned
            ), true);
        }

        update(update: ViewUpdate): void {
            const visibleRanges = visiblePreviewRanges(update.view);
            const containsLocalDocumentChange = update.transactions.some((transaction) =>
                transaction.docChanged && transaction.annotation(hostOrigin) !== true
            );
            const invalidated = update.docChanged && containsLocalDocumentChange
                ? changedPreviewRanges(update.state, update.changes)
                : [];
            const refreshRequested = update.transactions.some((transaction) =>
                transaction.effects.some((effect) => effect.is(refreshPreview))
            );
            const parserOrStateProgressed = !update.docChanged && update.transactions.length > 0;
            const refreshVisibleRanges = update.viewportChanged || refreshRequested || parserOrStateProgressed;
            if (update.docChanged || refreshVisibleRanges) {
                invalidated.push(...visibleRanges);
            }

            const ranges = mergeRanges(invalidated);
            if (ranges.length === 0) {
                return;
            }

            const mapped = this.decorations.map(update.changes);
            const withoutInvalidated = removePreviewDecorations(mapped, ranges);
            const additions = buildPreviewDecorations(update.state, ranges, onPreviewRangeScanned);
            const withAdditions = withoutInvalidated.update({add: additions, sort: true});
            this.decorations = retainVisiblePreviewDecorations(
                withAdditions,
                visibleRanges,
                update.state.doc.length
            );
        }
    }, {decorations: (value) => value.decorations});
}

function extractSourceChanges(changeSet: ChangeSet): SourceChangeTransaction | null {
    if (changeSet.empty) {
        return null;
    }

    const changes: SourceChange[] = [];
    changeSet.iterChanges((from, to, _fromNew, _toNew, inserted) => {
        changes.push(Object.freeze({from, to, inserted: inserted.toString()}));
    }, true);

    return {
        coordinateSpace: "pre-transaction",
        changes: Object.freeze(changes)
    };
}

function isWithinSourceMutationEnvelope(change: SourceChangeTransaction): boolean {
    if (change.changes.length === 0 || change.changes.length > SOURCE_NATIVE_MAX_EDITS) {
        return false;
    }
    const insertedLength = change.changes.reduce((total, item) => total + item.inserted.length, 0);
    return insertedLength <= SOURCE_NATIVE_MAX_INSERTED_UTF16;
}

function isValidSourceChange(change: SourceChange, documentLength: number): boolean {
    return Number.isSafeInteger(change.from)
        && Number.isSafeInteger(change.to)
        && change.from >= 0
        && change.to >= change.from
        && change.to <= documentLength;
}

/**
 * One CodeMirror projection of one host-authoritative Markdown document.
 *
 * The CodeMirror document is the source projection itself. This class keeps
 * no parallel Markdown buffer: source inspection reads the current
 * CodeMirror document, and host updates are classified with transaction
 * metadata before they enter the view.
 */
export class SourceNativeEditorCore {
    readonly view: EditorView;
    private disposed = false;

    constructor(options: SourceNativeEditorOptions) {
        this.view = new EditorView({
            state: EditorState.create({
                doc: options.initialSource,
                extensions: [
                    // A default EditorState recognizes CRLF/CR and canonicalizes
                    // them to LF. LF-only recognition retains CRLF source
                    // characters and their UTF-16 positions for the host
                    // logical-text boundary.
                    EditorState.lineSeparator.of("\n"),
                    markdown(),
                    createPreviewPlugin(options.onPreviewRangeScanned),
                    sourceNativeTheme,
                    EditorView.updateListener.of((update) => {
                        if (this.disposed || !update.docChanged) {
                            return;
                        }

                        for (const transaction of update.transactions) {
                            if (this.disposed || !transaction.docChanged || transaction.annotation(hostOrigin) === true) {
                                continue;
                            }

                            const change = extractSourceChanges(transaction.changes);
                            if (change !== null) {
                                options.onLocalChange(change);
                            }
                        }
                    })
                ]
            }),
            parent: options.parent,
            dispatchTransactions: (transactions, view) => {
                if (this.disposed) {
                    return;
                }

                const localTransactions = transactions.filter((transaction) =>
                    transaction.docChanged && transaction.annotation(hostOrigin) !== true
                );
                // A single dispatch may only commit one source transaction for this target. A
                // multi-transaction batch is rejected before any of it reaches the projection.
                if (localTransactions.length > 1) {
                    return;
                }
                if (localTransactions.length === 1) {
                    const change = extractSourceChanges(localTransactions[0].changes);
                    if (change === null
                        || !isWithinSourceMutationEnvelope(change)
                        || options.onBeforeLocalChange?.(change) === false) {
                        return;
                    }
                }

                view.update(transactions);
            }
        });
    }

    /** Read-only inspection of the current projected source. */
    get source(): string {
        return this.view.state.doc.toString();
    }

    /**
     * Count source-changing events represented by one pre-transaction proposal. This is read
     * before CodeMirror commits the projection and lets the attachment store the exact revision
     * delta an authoritative host transaction can produce.
     */
    effectiveSourceChangeCount(change: SourceChangeTransaction): number {
        if (this.disposed) {
            return 0;
        }
        return change.changes.reduce((count, item) => {
            if (!isValidSourceChange(item, this.view.state.doc.length)) {
                return count;
            }
            return this.view.state.doc.sliceString(item.from, item.to) === item.inserted
                ? count
                : count + 1;
        }, 0);
    }

    /**
     * Apply a host-authoritative source projection. The annotation is part of
     * the transaction, so the update cannot be mistaken for a local proposal.
     */
    applyHostSource(source: string): boolean {
        if (this.disposed || this.source === source) {
            return false;
        }

        this.view.dispatch({
            changes: {
                from: 0,
                to: this.view.state.doc.length,
                insert: source
            },
            annotations: hostOrigin.of(true)
        });
        return true;
    }

    /** Apply one exact UTF-16 host edit without creating a local proposal. */
    applyHostEdit(change: SourceChange): boolean {
        if (this.disposed || !isValidSourceChange(change, this.view.state.doc.length)) {
            return false;
        }
        if (this.view.state.doc.sliceString(change.from, change.to) === change.inserted) {
            return false;
        }

        this.view.dispatch({
            changes: {
                from: change.from,
                to: change.to,
                insert: change.inserted
            },
            annotations: hostOrigin.of(true)
        });
        return true;
    }

    /** Refresh visible view-only Markdown decorations without changing source text. */
    refreshPreview(): void {
        if (!this.disposed) {
            this.view.dispatch({effects: refreshPreview.of(undefined)});
        }
    }

    dispose(): void {
        if (this.disposed) {
            return;
        }

        this.disposed = true;
        this.view.destroy();
    }
}
