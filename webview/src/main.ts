import "@milkdown/crepe/theme/common/style.css";
import "@milkdown/crepe/theme/frame.css";
import "katex/dist/katex.min.css";
import "./style.css";
import "./styles/mermaid.css";
import {MarkFlowEditorSession} from "./app/editor-session";

const session = new MarkFlowEditorSession();
void session.initEditor();
