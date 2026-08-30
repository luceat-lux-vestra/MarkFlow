import "katex/dist/katex.min.css";
import "@milkdown/crepe/theme/common/style.css";
// Bundled light + dark Crepe theme variables. Import dark first then light so light
// wins the initial render; applyRuntimeAppearance() overrides with !important at runtime.
import "@milkdown/crepe/theme/classic-dark.css";
import "@milkdown/crepe/theme/classic.css";
import "@milkdown/crepe/theme/frame.css";
import "../style.css";
import "../styles/mermaid.css";

export const bootstrapMarkFlowEditor = async () => {
    const {MarkFlowEditorSession} = await import("./editor-session");
    const session = new MarkFlowEditorSession();
    await session.initEditor();
};
