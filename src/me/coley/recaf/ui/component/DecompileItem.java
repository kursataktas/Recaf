package me.coley.recaf.ui.component;

import java.util.Optional;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import org.controlsfx.control.PropertySheet.Item;
import org.controlsfx.property.editor.PropertyEditor;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.image.Image;
import jregex.Matcher;
import jregex.Pattern;
import me.coley.recaf.Input;
import me.coley.recaf.Logging;
import me.coley.recaf.bytecode.Asm;
import me.coley.recaf.ui.FxCode;
import me.coley.recaf.util.*;
import me.coley.memcompiler.Compiler;

/**
 * Item for decompiling classes / methods.
 * 
 * @author Matt
 */
public class DecompileItem implements Item {
	//@formatter:off
	private static final String[] KEYWORDS = new String[] { "abstract", "assert", "boolean", "break", "byte", "case", "catch",
			"char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
			"float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new",
			"package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
			"synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while" };
	private static final String KEYWORD_PATTERN = "\\b(" + String.join("|", KEYWORDS) + ")\\b";
	private static final String STRING_PATTERN = "\"([^\"\\\\]|\\\\.)*\"";
	private static final String CONST_HEX_PATTERN = "(0[xX][0-9a-fA-F]+)+";
	private static final String CONST_VAL_PATTERN = "(\\b([\\d._]*[\\d])\\b)+|(true|false|null)";
	private static final String CONST_PATTERN = CONST_HEX_PATTERN + "|" + CONST_VAL_PATTERN;
	private static final String COMMENT_SINGLE_PATTERN = "//[^\n]*";
	private static final String COMMENT_MULTI_SINGLE_PATTERN = "/[*](.|\n|\r)+?\\*/";
	private static final String COMMENT_MULTI_JAVADOC_PATTERN = "/[*]{2}(.|\n|\r)+?\\*/";
	private static final String ANNOTATION_PATTERN = "\\B(@[\\w]+)\\b";
	private static final Pattern PATTERN = new Pattern(
			"({COMMENTDOC}" + COMMENT_MULTI_JAVADOC_PATTERN + ")" +
			"|({COMMENTMULTI}" + COMMENT_MULTI_SINGLE_PATTERN + ")" +
			"|({COMMENTLINE}" + COMMENT_SINGLE_PATTERN + ")" +
			"|({KEYWORD}" + KEYWORD_PATTERN + ")" +
			"|({STRING}" + STRING_PATTERN + ")" +
			"|({ANNOTATION}" + ANNOTATION_PATTERN + ")" +
			"|({CONSTPATTERN}" + CONST_PATTERN + ")");
	//@formatter:on

	/**
	 * Class to decompile.
	 */
	private final ClassNode cn;
	/**
	 * Optional: Method to decompile. If not {@code null} then only this method
	 * is decompiled.
	 */
	private final MethodNode mn;

	public DecompileItem(ClassNode cn) {
		this(cn, null);
	}

	public DecompileItem(ClassNode cn, MethodNode mn) {
		this.mn = mn;
		this.cn = cn;
	}

	/**
	 * Create new stage with decompiled text.
	 */
	public void decompile() {
		CFRPipeline pipe = new CFRPipeline(cn, mn);
		String decompile = pipe.decompile();
		String postfix = pipe.getTitlePostfix();
		// Create decompilation area
		FxDecompile code = new FxDecompile(decompile, postfix);
		code.show();
	}
	
	/**
	 * Currently unused since recompiling from method-only decompile is already
	 * unsupported <i>(for now)</i>.
	 * 
	 * @param cn
	 *            Node to extract method from.
	 * @return ClassNode containing only the {@link #mn target method}.
	 */
	@SuppressWarnings("unused")
	private ClassNode strip(ClassNode cn) {
		ClassNode copy = new ClassNode();
		copy.visit(cn.version, cn.access, cn.name, cn.signature, cn.superName, cn.interfaces.stream().toArray(String[]::new));
		copy.methods.add(mn);
		return copy;
	}

	/* boiler-plate for displaying button that opens the stage. */

	@Override
	public Optional<Class<? extends PropertyEditor<?>>> getPropertyEditorClass() {
		return JavaFX.optional(DecompileButton.class);
	}

	@Override
	public Class<?> getType() {
		return Object.class;
	}

	@Override
	public String getCategory() {
		return Lang.get("ui.bean.class");
	}

	@Override
	public String getName() {
		return Lang.get("ui.bean.class.decompile.name");
	}

	@Override
	public String getDescription() {
		return Lang.get("ui.bean.class.decompile.desc");
	}

	@Override
	public Object getValue() {
		return null;
	}

	@Override
	public void setValue(Object value) {}

	@Override
	public Optional<ObservableValue<? extends Object>> getObservableValue() {
		return JavaFX.optionalObserved(null);
	}

	public class FxDecompile extends FxCode {
		private final String postfix;

		protected FxDecompile(String initialText, String postfix) {
			super(initialText, ScreenUtil.prefWidth(), ScreenUtil.prefHeight());
			this.postfix = postfix;
		}

		@Override
		protected void setupCode(String initialText) {
			super.setupCode(initialText);
			// Add line numbers.
			code.setParagraphGraphicFactory(LineNumberFactory.get(code));
			// Allow recompilation if user is running on a JDK.
			// TODO: Bring back single method support
			if (Misc.isJDK() && mn == null) {
				ContextMenu ctx = new ContextMenu();
				ctx.getStyleClass().add("code-context-menu");
				ctx.getItems().add(new ActionMenuItem(Lang.get("ui.bean.class.recompile.name"), () -> recompile(code)));
				code.setContextMenu(ctx);
			}
		}

		@Override
		protected String createTitle() {
			return Lang.get("ui.bean.class.decompile.name") + ": " + postfix;
		}

		@Override
		protected Image createIcon() {
			return Icons.CL_CLASS;
		}

		@Override
		protected Pattern createPattern() {
			return PATTERN;
		}

		@Override
		protected String getStyleClass(Matcher matcher) {
			//@formatter:off
			return 	  matcher.group("STRING")       != null ? "string"
					: matcher.group("KEYWORD")      != null ? "keyword"
					: matcher.group("COMMENTDOC")   != null ? "comment-javadoc"
					: matcher.group("COMMENTMULTI") != null ? "comment-multi"
					: matcher.group("COMMENTLINE")  != null ? "comment-line"
					: matcher.group("CONSTPATTERN") != null ? "const"
					: matcher.group("ANNOTATION")   != null ? "annotation" : null;
			//@formatter:on
		}

		/**
		 * Uses the decompiled code to recompile.
		 */
		private void recompile(CodeArea codeText) {
			OutputStream out = null;
			try {
				String srcText = codeText.getText();
				// TODO: For dependencies in agent-mode the jar/classes 
				// should be fetched from the class-path.
				Compiler compiler = new Compiler();
				if (Input.get().input != null) {
					compiler.getClassPath().add(Input.get().input.getAbsolutePath());
				} else {
					// TODO: Add instrumented classpath
				}
				compiler.getDebug().sourceName = true;
				compiler.getDebug().lineNumbers = true;
				compiler.getDebug().variables = true;
				compiler.addUnit(cn.name.replace("/", "."), srcText);
				if (mn != null) {
					Logging.error("Single-method recompilation unsupported, please decompile the full class");
					return;
				}
				out = new OutputStream() {
					private StringBuilder string = new StringBuilder();

					@Override
					public void write(int b) throws IOException {
						this.string.append((char) b);
					}

					// Netbeans IDE automatically overrides this toString()
					public String toString() {
						return this.string.toString();
					}
				};
				PrintStream errOut = new PrintStream(out);
				compiler.setOut(errOut);
				if (!compiler.compile()) {
					Logging.error("Could not recompile!");
				}
				// Iterate over compiled units. This will include inner classes
				// and the like.
				// TODO: Have alternate logic for single-method replacement
				for (String unit : compiler.getUnitNames()) {
					byte[] code = compiler.getUnitCode(unit);
					ClassNode newValue = Asm.getNode(code);
					Input.get().getClasses().put(cn.name, newValue);
					Logging.info("Recompiled '" + cn.name + "' - size:" + code.length, 1);
				}
			} catch (Exception e) {
				if (out == null) {
					Logging.error(e);
				} else {
					Logging.error(out.toString(), true);
				}
			}
		}
	}

	/**
	 * Button to pop up decopilation window.
	 * 
	 * @author Matt
	 */
	public static class DecompileButton implements PropertyEditor<Object> {
		private final DecompileItem item;

		public DecompileButton(Item item) {
			this.item = (DecompileItem) item;
		}

		@Override
		public Node getEditor() {
			Button button = new Button(Lang.get("ui.bean.class.decompile.name"));
			button.setOnAction(e -> item.decompile());
			return button;
		}

		@Override
		public Object getValue() {
			return null;
		}

		@Override
		public void setValue(Object value) {}
	}
}
