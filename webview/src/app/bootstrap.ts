import "@milkdown/crepe/theme/common/style.css";
import "@milkdown/crepe/theme/frame.css";
import "../style.css";
import "../styles/mermaid.css";

export const bootstrapMarkFlowEditor = async () => {
    const {MarkFlowEditorSession} = await import("./editor-session");
    const session = new MarkFlowEditorSession();
    await session.initEditor();
};
