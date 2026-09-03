import {markdown} from "@codemirror/lang-markdown";
import {syntaxTree} from "@codemirror/language";
import {
    Annotation,
    EditorState,
    StateEffect,
    StateField
} from "@codemirror/state";
import type {ChangeSet, Range} from "@codemirror/state";
import {Decoration, EditorView} from "@codemirror/view";

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
    readonly onLocalChange: (change: SourceChangeTransaction) => void;
}

const hostOrigin = Annotation.define<true>();
const refreshPreview = StateEffect.define<void>();

const sourceNativeTheme = EditorView.baseTheme({
    ".cm-source-native-emphasis": {
        fontStyle: "italic"
    },
    ".cm-source-native-strong": {
        fontWeight: "bold"
    }
});

function buildPreviewDecorations(state: EditorState): DecorationSet {
    const ranges: Range<Decoration>[] = [];

    syntaxTree(state).iterate({
        enter(node) {
            const className = node.name === "Emphasis"
                ? "cm-source-native-emphasis"
                : node.name === "StrongEmphasis"
                    ? "cm-source-native-strong"
                    : undefined;
            if (className !== undefined) {
                ranges.push(Decoration.mark({class: className}).range(node.from, node.to));
            }
        }
    });

    return Decoration.set(ranges, true);
}

type DecorationSet = ReturnType<typeof Decoration.set>;

const previewDecorations = StateField.define<DecorationSet>({
    create: buildPreviewDecorations,
    update(value, transaction) {
        if (transaction.docChanged || transaction.effects.some((effect) => effect.is(refreshPreview))) {
            return buildPreviewDecorations(transaction.state);
        }
        return value;
    },
    provide: (field) => EditorView.decorations.from(field)
});

function extractSourceChanges(changeSet: ChangeSet): SourceChangeTransaction | null {
    if (changeSet.empty) {
        return null;
    }

    const changes: SourceChange[] = [];
    changeSet.iterChanges((from, to, _fromNew, _toNew, inserted) => {
        changes.push({from, to, inserted: inserted.toString()});
    }, true);

    return {
        coordinateSpace: "pre-transaction",
        changes: Object.freeze(changes)
    };
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
                    // The default recognizes CRLF/CR and canonicalizes them to LF.
                    // LF-only recognition retains CRLF source characters and their
                    // UTF-16 positions for the host logical-text boundary.
                    EditorState.lineSeparator.of("\n"),
                    markdown(),
                    previewDecorations,
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
                if (!this.disposed) {
                    view.update(transactions);
                }
            }
        });
    }

    /** Read-only inspection of the current projected source. */
    get source(): string {
        return this.view.state.doc.toString();
    }

    /**
     * Apply a host-authoritative source projection. The annotation is part of
     * the transaction, so the update cannot be mistaken for a local proposal.
     */
    applyHostSource(source: string): void {
        if (this.disposed || this.source === source) {
            return;
        }

        this.view.dispatch({
            changes: {
                from: 0,
                to: this.view.state.doc.length,
                insert: source
            },
            annotations: hostOrigin.of(true)
        });
    }

    /** Rebuild view-only Markdown decorations without changing source text. */
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
