package com.dslplatform.json.processor;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.Configuration;
import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonConverter;
import com.dslplatform.json.runtime.Settings;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.lang.reflect.Type;
import java.util.*;

import static com.dslplatform.json.processor.Context.nonGenericObject;
import static com.dslplatform.json.processor.Context.typeOrClass;

@SupportedAnnotationTypes({"com.dslplatform.json.CompiledJson", "com.dslplatform.json.JsonAttribute", "com.dslplatform.json.JsonConverter", "com.fasterxml.jackson.annotation.JsonCreator", "javax.json.bind.annotation.JsonbCreator"})
public class CompiledJsonAnnotationProcessor extends AbstractProcessor {

	private static final Set<String> JsonIgnore;
	private static final Map<String, List<Analysis.AnnotationMapping<Boolean>>> NonNullable;
	private static final Map<String, String> PropertyAlias;
	private static final Map<String, List<Analysis.AnnotationMapping<Boolean>>> JsonRequired;
	private static final Set<String> Constructors;
	private static final Map<String, String> Indexes;
	private static final Map<String, OptimizedConverter> InlinedConverters;
	private static final Map<String, String> Defaults;

	private static final String CONFIG = "META-INF/services/com.dslplatform.json.Configuration";

	private static final String GRADLE_OPTION_ISOLATING = "org.gradle.annotation.processing.isolating";
	private static final String GRADLE_OPTION_AGGREGATING = "org.gradle.annotation.processing.aggregating";

	private enum Options {
		LOG_LEVEL("dsljson.loglevel"),
		ANNOTATION("dsljson.annotation"),
		UNKNOWN("dsljson.unknown"),
		JACKSON("dsljson.jackson"),
		JSONB("dsljson.jsonb"),
		CONFIGURATION("dsljson.configuration");

		final String value;

		Options(String value) {
			this.value = value;
		}
	}

	static {
		JsonIgnore = new HashSet<>();
		JsonIgnore.add("com.fasterxml.jackson.annotation.JsonIgnore");
		JsonIgnore.add("javax.json.bind.annotation.JsonbTransient");
		NonNullable = new HashMap<>();
		NonNullable.put("javax.validation.constraints.NotNull", null);
		NonNullable.put("javax.annotation.Nonnull", null);
		NonNullable.put("android.support.annotation.NonNull", null);
		NonNullable.put("org.jetbrains.annotations.NotNull", null);
		NonNullable.put(
				"javax.json.bind.annotation.JsonbNillable",
				Arrays.asList(
						new Analysis.AnnotationMapping<>("value()", null),
						new Analysis.AnnotationMapping<>("value()", true)));
		NonNullable.put(
				"javax.json.bind.annotation.JsonbProperty",
				Collections.singletonList(new Analysis.AnnotationMapping<>("nillable()", true)));
		PropertyAlias = new HashMap<>();
		PropertyAlias.put("com.fasterxml.jackson.annotation.JsonProperty", "value()");
		PropertyAlias.put("com.google.gson.annotations.SerializedName", "value()");
		PropertyAlias.put("javax.json.bind.annotation.JsonbProperty", "value()");
		JsonRequired = new HashMap<>();
		JsonRequired.put(
				"com.fasterxml.jackson.annotation.JsonProperty",
				Collections.singletonList(new Analysis.AnnotationMapping<>("required()", true)));
		Constructors = new HashSet<>();
		Constructors.add("com.fasterxml.jackson.annotation.JsonCreator");
		Constructors.add("javax.json.bind.annotation.JsonbCreator");
		Indexes = new HashMap<>();
		Indexes.put("com.fasterxml.jackson.annotation.JsonProperty", "index()");
		InlinedConverters = new HashMap<>();
		InlinedConverters.put("int", new OptimizedConverter("com.dslplatform.json.NumberConverter", "INT_WRITER", "serialize", "INT_READER", "deserializeInt", "0"));
		InlinedConverters.put("int[]", new OptimizedConverter("com.dslplatform.json.NumberConverter", "INT_ARRAY_WRITER", "serialize", "INT_ARRAY_READER"));
		InlinedConverters.put("java.lang.Integer", new OptimizedConverter("com.dslplatform.json.NumberConverter", "INT_WRITER", "serialize", "NULLABLE_INT_READER", "deserializeInt", null));
		InlinedConverters.put("long", new OptimizedConverter("com.dslplatform.json.NumberConverter", "LONG_WRITER", "serialize", "LONG_READER", "deserializeLong", "0L"));
		InlinedConverters.put("long[]", new OptimizedConverter("com.dslplatform.json.NumberConverter", "LONG_ARRAY_WRITER", "serialize", "LONG_ARRAY_READER"));
		InlinedConverters.put("java.lang.Long", new OptimizedConverter("com.dslplatform.json.NumberConverter", "LONG_WRITER", "serialize", "LONG_READER", "deserializeLong", null));
		InlinedConverters.put("float", new OptimizedConverter("com.dslplatform.json.NumberConverter", "FLOAT_WRITER", "serialize", "FLOAT_READER", "deserializeFloat", "0.0"));
		InlinedConverters.put("float[]", new OptimizedConverter("com.dslplatform.json.NumberConverter", "FLOAT_ARRAY_WRITER", "serialize", "FLOAT_ARRAY_READER"));
		InlinedConverters.put("java.lang.Float", new OptimizedConverter("com.dslplatform.json.NumberConverter", "FLOAT_WRITER", "serialize", "NULLABLE_FLOAT_READER", "deserializeFloat", null));
		InlinedConverters.put("double", new OptimizedConverter("com.dslplatform.json.NumberConverter", "DOUBLE_WRITER", "serialize", "DOUBLE_READER", "deserializeDouble", "0.0"));
		InlinedConverters.put("double[]", new OptimizedConverter("com.dslplatform.json.NumberConverter", "DOUBLE_ARRAY_WRITER", "serialize", "DOUBLE_ARRAY_READER"));
		InlinedConverters.put("java.lang.Double", new OptimizedConverter("com.dslplatform.json.NumberConverter", "DOUBLE_WRITER", "serialize", "NULLABLE_DOUBLE_READER", "deserializeDouble", null));
		InlinedConverters.put("boolean", new OptimizedConverter("com.dslplatform.json.BoolConverter", "WRITER", "serialize", "READER", "deserialize", "false"));
		InlinedConverters.put("boolean[]", new OptimizedConverter("com.dslplatform.json.BoolConverter", "ARRAY_WRITER", "serialize", "ARRAY_READER"));
		InlinedConverters.put("java.lang.Boolean", new OptimizedConverter("com.dslplatform.json.BoolConverter", "WRITER", "serialize", "NULLABLE_READER", "deserialize", null));
		InlinedConverters.put("java.lang.String", new OptimizedConverter("com.dslplatform.json.StringConverter", "WRITER", "serialize", "READER", "deserialize", null));
		InlinedConverters.put("java.util.UUID", new OptimizedConverter("com.dslplatform.json.UUIDConverter", "WRITER", "serialize", "READER", "deserialize", null));
		InlinedConverters.put("java.time.LocalDate", new OptimizedConverter("com.dslplatform.json.JavaTimeConverter", "LOCAL_DATE_WRITER", "serialize", "LOCAL_DATE_READER", "deserializeLocalDate", null));
		InlinedConverters.put("java.time.OffsetDateTime", new OptimizedConverter("com.dslplatform.json.JavaTimeConverter", "DATE_TIME_READER", "serialize", "DATE_TIME_WRITER", "deserializeDateTime", null));
		Defaults = new HashMap<>();
		Defaults.put("byte", "(byte)0");
		Defaults.put("boolean", "false");
		Defaults.put("int", "0");
		Defaults.put("long", "0L");
		Defaults.put("short", "(short)0");
		Defaults.put("double", "0.0");
		Defaults.put("float", "0.0f");
		Defaults.put("char", "'\0'");
		Defaults.put("java.util.OptionalLong", "java.util.OptionalLong.empty()");
		Defaults.put("java.util.OptionalInt", "java.util.OptionalInt.empty()");
		Defaults.put("java.util.OptionalDouble", "java.util.OptionalDouble.empty()");
		Defaults.put("java.util.Optional", "java.util.Optional.empty()");
	}

	private LogLevel logLevel = LogLevel.ERRORS;
	private AnnotationUsage annotationUsage = AnnotationUsage.IMPLICIT;
	private UnknownTypes unknownTypes = UnknownTypes.ERROR;
	private boolean withJackson = false;
	private boolean withJsonb = false;
	private String configurationFileName = null;

	private TypeElement jacksonCreatorElement;
	private DeclaredType jacksonCreatorType;
	private TypeElement jsonbCreatorElement;
	private DeclaredType jsonbCreatorType;

	@Override
	public synchronized void init(ProcessingEnvironment processingEnv) {
		super.init(processingEnv);
		Map<String, String> options = processingEnv.getOptions();
		String ll = options.get(Options.LOG_LEVEL.value);
		if (ll != null && ll.length() > 0) {
			logLevel = LogLevel.valueOf(ll);
		}
		String au = options.get(Options.ANNOTATION.value);
		if (au != null && au.length() > 0) {
			annotationUsage = AnnotationUsage.valueOf(au);
		}
		String unk = options.get(Options.UNKNOWN.value);
		if (unk != null && unk.length() > 0) {
			unknownTypes = UnknownTypes.valueOf(unk);
		}
		String jks = options.get(Options.JACKSON.value);
		if (jks != null && jks.length() > 0) {
			withJackson = Boolean.parseBoolean(jks);
		}
		String jsb = options.get(Options.JSONB.value);
		if (jsb != null && jsb.length() > 0) {
			withJsonb = Boolean.parseBoolean(jsb);
		}
		String con = options.get(Options.CONFIGURATION.value);
		if (con != null && con.length() > 0) {
			configurationFileName = con;
		}
		jacksonCreatorElement = processingEnv.getElementUtils().getTypeElement("com.fasterxml.jackson.annotation.JsonCreator");
		jacksonCreatorType = jacksonCreatorElement != null ? processingEnv.getTypeUtils().getDeclaredType(jacksonCreatorElement) : null;
		jsonbCreatorElement = processingEnv.getElementUtils().getTypeElement("javax.json.bind.annotation.JsonbCreator");
		jsonbCreatorType = jsonbCreatorElement != null ? processingEnv.getTypeUtils().getDeclaredType(jsonbCreatorElement) : null;
	}

	@Override
	public Set<String> getSupportedOptions() {
		Set<String> options = new HashSet<>();
		for (Options option : Options.values()) {
			options.add(option.value);
		}
		//TODO: this is not fully correct. It should be only configurationFileName.isEmpty() but that requires additional configuration
		options.add(configurationFileName == null || configurationFileName.isEmpty() ? GRADLE_OPTION_ISOLATING : GRADLE_OPTION_AGGREGATING);
		return options;
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (roundEnv.processingOver() || annotations.isEmpty()) {
			return false;
		}
		final DslJson<Object> dslJson = new DslJson<>(Settings.withRuntime().includeServiceLoader(getClass().getClassLoader()));
		Set<Type> knownEncoders = dslJson.getRegisteredEncoders();
		Set<Type> knownDecoders = dslJson.getRegisteredDecoders();
		Set<String> allTypes = new HashSet<>();
		for (Type t : knownEncoders) {
			if (knownDecoders.contains(t)) {
				allTypes.add(t.getTypeName());
			}
		}
		final Analysis analysis = new Analysis(
				processingEnv,
				annotationUsage,
				logLevel,
				allTypes,
				rawClass -> {
					try {
						Class<?> raw = Class.forName(rawClass);
						return dslJson.canSerialize(raw) && dslJson.canDeserialize(raw);
					} catch (Exception ignore) {
						return false;
					}
				},
				JsonIgnore,
				NonNullable,
				PropertyAlias,
				JsonRequired,
				Constructors,
				Indexes,
				unknownTypes,
				false,
				true,
				true,
				true);
		Set<? extends Element> compiledJsons = roundEnv.getElementsAnnotatedWith(analysis.compiledJsonElement);
		Set<? extends Element> jacksonCreators = withJackson && jacksonCreatorElement != null ? roundEnv.getElementsAnnotatedWith(jacksonCreatorElement) : new HashSet<>();
		Set<? extends Element> jsonbCreators = withJsonb && jsonbCreatorElement != null ? roundEnv.getElementsAnnotatedWith(jsonbCreatorElement) : new HashSet<>();
		if (!compiledJsons.isEmpty() || !jacksonCreators.isEmpty() || !jsonbCreators.isEmpty()) {
			Set<? extends Element> jsonConverters = roundEnv.getElementsAnnotatedWith(analysis.converterElement);
			Map<String, Element> configurations = analysis.processConverters(jsonConverters);
			if (!configurations.isEmpty() && "".equals(configurationFileName)) {
				for (Map.Entry<String, Element> kv : configurations.entrySet()) {
					if (logLevel.isVisible(LogLevel.INFO)) {
						processingEnv.getMessager().printMessage(
								Diagnostic.Kind.WARNING,
								"Configuration file is disabled, but @" + JsonConverter.class.getName() + " which implements " + Configuration.class.getName() + " found: '" + kv.getKey() + "'. Manual converter registration with DslJson is required.",
								kv.getValue());
					}
				}
				return false;
			}
			analysis.processAnnotation(analysis.compiledJsonType, compiledJsons);
			if (!jacksonCreators.isEmpty() && jacksonCreatorType != null) {
				analysis.processAnnotation(jacksonCreatorType, jacksonCreators);
			}
			if (!jsonbCreators.isEmpty() && jsonbCreatorType != null) {
				analysis.processAnnotation(jsonbCreatorType, jsonbCreators);
			}
			Map<String, StructInfo> structs = analysis.analyze();
			if (analysis.hasError()) {
				return false;
			}

			final Map<String, StructInfo> generatedFiles = new HashMap<>();
			final List<Element> originatingElements = new ArrayList<>();

			for (Map.Entry<String, StructInfo> entry : structs.entrySet()) {
				StructInfo structInfo = entry.getValue();
				if (structInfo.type == ObjectType.CLASS && structInfo.attributes.isEmpty()) {
					continue;
				}

				String classNamePath = findConverterName(entry.getValue());
				try {
					JavaFileObject converterFile = processingEnv.getFiler().createSourceFile(classNamePath, structInfo.element);
					try (Writer writer = converterFile.openWriter()) {
						buildCode(writer, entry.getKey(), structInfo, structs, allTypes);
						generatedFiles.put(classNamePath, structInfo);
						originatingElements.add(structInfo.element);
					} catch (IOException e) {
						processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
								"Failed saving compiled json serialization file " + classNamePath);
					}
				} catch (IOException e) {
					processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
							"Failed creating compiled json serialization file " + classNamePath);
				}
			}

			final List<String> allConfigurations = new ArrayList<>(configurations.keySet());
			if (configurationFileName != null) {
				try {
					FileObject configFile = processingEnv.getFiler()
							.createSourceFile(configurationFileName, originatingElements.toArray(new Element[0]));
					try (Writer writer = configFile.openWriter()) {
						if (!buildRootConfiguration(writer, configurationFileName, generatedFiles, processingEnv))
							return false;
						allConfigurations.add(configurationFileName);
					} catch (Exception e) {
						processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
								"Failed saving configuration file " + configurationFileName);
					}
				} catch (IOException e) {
					processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
							"Failed creating configuration file " + configurationFileName);
				}
			}
			if (!allConfigurations.isEmpty()) {
				originatingElements.addAll(configurations.values());
				saveToServiceConfigFile(allConfigurations, originatingElements);
			}
		}
		return false;
	}

	private void saveToServiceConfigFile(List<String> configurations, List<Element> elements) {
		try {
			FileObject configFile = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", CONFIG, elements.toArray(new Element[0]));
			try (Writer writer = configFile.openWriter()) {
				for (String conf : configurations) {
					writer.write(conf);
					writer.write('\n');
				}
			} catch (Exception e) {
				processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed saving config file " + CONFIG);
			}
		} catch (IOException e) {
			processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed creating config file " + CONFIG);
		}
	}

	@Override
	public SourceVersion getSupportedSourceVersion() {
		SourceVersion latest = SourceVersion.latest();
		if ("RELEASE_9".equals(latest.name())) {
			return latest;
		} else if (latest.name().length() > "RELEASE_9".length()) {
			return latest;
		}
		return SourceVersion.RELEASE_8;
	}

	static String findConverterName(StructInfo structInfo) {
		int dotIndex = structInfo.binaryName.lastIndexOf('.');
		String className = structInfo.binaryName.substring(dotIndex + 1);
		if (dotIndex == -1) return String.format("_%s_DslJsonConverter", className);
		String packageName = structInfo.binaryName.substring(0, dotIndex);
		Package packageClass = Package.getPackage(packageName);
		boolean useDslPackage = packageClass != null && packageClass.isSealed() || structInfo.binaryName.startsWith("java.");
		return String.format("%s%s._%s_DslJsonConverter", useDslPackage ? "dsl_json." : "", packageName, className);
	}

	private static void buildCode(
			final Writer code,
			final String className,
			final StructInfo si,
			final Map<String, StructInfo> structs,
			final Set<String> knownTypes) throws IOException {
		final Context context = new Context(code, InlinedConverters, Defaults, structs, knownTypes);
		final ConverterTemplate converterTemplate = new ConverterTemplate(context);
		final EnumTemplate enumTemplate = new EnumTemplate(context);

		final String generateFullClassName = findConverterName(si);
		final int dotIndex = generateFullClassName.lastIndexOf('.');
		final String generateClassName = generateFullClassName.substring(dotIndex + 1, generateFullClassName.length());
		if (dotIndex != -1) {
			final String generatePackage = generateFullClassName.substring(0, dotIndex);
			code.append("package ").append(generatePackage).append(";\n\n");
		}
		code.append("public class ").append(generateClassName).append(" implements com.dslplatform.json.Configuration {\n");
		code.append("\tprivate static final java.nio.charset.Charset utf8 = java.nio.charset.Charset.forName(\"UTF-8\");\n");
		code.append("\t@Override\n");
		code.append("\tpublic void configure(com.dslplatform.json.DslJson json) {\n");

		if (si.type == ObjectType.CLASS && si.constructor != null && !si.attributes.isEmpty()) {
			String objectFormatConverterName = "converter";
			if (si.formats.contains(CompiledJson.Format.OBJECT)) {
				code.append("\t\tObjectFormatConverter objectConverter = new ObjectFormatConverter(json);\n");
				objectFormatConverterName = "objectConverter";
			}
			if (si.formats.contains(CompiledJson.Format.ARRAY)) {
				code.append("\t\tArrayFormatConverter arrayConverter = new ArrayFormatConverter(json);\n");
				objectFormatConverterName = "arrayConverter";
			}
			if (si.formats.contains(CompiledJson.Format.OBJECT) && si.formats.contains(CompiledJson.Format.ARRAY)) {
				code.append("\t\tcom.dslplatform.json.runtime.FormatDescription description = new com.dslplatform.json.runtime.FormatDescription(\n");
				code.append("\t\t\t").append(className).append(".class,\n");
				code.append("\t\t\tobjectConverter,\n");
				code.append("\t\t\tarrayConverter,\n");
				if (si.isObjectFormatFirst) code.append("\t\t\ttrue,\n");
				else code.append("\t\t\tfalse,\n");
				String typeAlias = si.deserializeName.isEmpty() ? className : si.deserializeName;
				code.append("\t\t\t\"").append(typeAlias).append("\",\n");
				code.append("\t\t\tjson);\n");
				if (si.hasEmptyCtor()) {
					code.append("\t\tjson.registerBinder(").append(className).append(".class, description);\n");
				}
				code.append("\t\tjson.registerReader(").append(className).append(".class, description);\n");
				code.append("\t\tjson.registerWriter(").append(className).append(".class, description);\n");
			} else {
				if (si.hasEmptyCtor()) {
					code.append("\t\tjson.registerBinder(").append(className).append(".class, ").append(objectFormatConverterName).append(");\n");
				}
				code.append("\t\tjson.registerReader(").append(className).append(".class, ").append(objectFormatConverterName).append(");\n");
				code.append("\t\tjson.registerWriter(").append(className).append(".class, ").append(objectFormatConverterName).append(");\n");
			}
		} else if (si.type == ObjectType.CONVERTER) {
			String type = typeOrClass(nonGenericObject(className), className);
			code.append("\t\tjson.registerWriter(").append(type).append(", ").append(si.converter).append(".").append(si.converterWriter).append(");\n");
			code.append("\t\tjson.registerReader(").append(type).append(", ").append(si.converter).append(".").append(si.converterReader).append(");\n");
		} else if (si.type == ObjectType.ENUM) {
			code.append("\t\tEnumConverter enumConverter = new EnumConverter();\n");
			code.append("\t\tjson.registerWriter(").append(className).append(".class, enumConverter);\n");
			code.append("\t\tjson.registerReader(").append(className).append(".class, enumConverter);\n");
		}

		if (si.type == ObjectType.MIXIN && !si.implementations.isEmpty()) {
			mixin(code, si.deserializeAs != null, si, className);
		}
		if (si.type == ObjectType.MIXIN && si.deserializeAs != null) {
			String typeMixin = typeOrClass(nonGenericObject(className), className);
			StructInfo target = si.deserializeTarget();
			code.append("\t\tjson.registerReader(").append(typeMixin).append(", ");
			if (!target.formats.contains(CompiledJson.Format.OBJECT)) {
				code.append("new ").append(findConverterName(target)).append(".ArrayFormatConverter(json));\n");
			} else if (!target.formats.contains(CompiledJson.Format.ARRAY)) {
				code.append("new ").append(findConverterName(target)).append(".ObjectFormatConverter(json));\n");
			}
		}

		code.append("\t}\n");

		if (si.type == ObjectType.CLASS && !si.attributes.isEmpty()) {
			if (si.hasEmptyCtor()) {
				if (si.formats.contains(CompiledJson.Format.OBJECT)) {
					converterTemplate.emptyCtorObject(si, className);
				}
				if (si.formats.contains(CompiledJson.Format.ARRAY)) {
					converterTemplate.emptyCtorArray(si, className);
				}
			} else if (si.constructor != null) {
				if (si.formats.contains(CompiledJson.Format.OBJECT)) {
					converterTemplate.fromCtorObject(si, className);
				}
				if (si.formats.contains(CompiledJson.Format.ARRAY)) {
					converterTemplate.fromCtorArray(si, className);
				}
			}
		} else if (si.type == ObjectType.ENUM) {
			enumTemplate.create(si, className);
		}

		code.append("}\n");
	}

	private static void mixin(final Writer code, final boolean writeOnly, final StructInfo si, final String className) throws IOException {
		final String mixinType = writeOnly ? "MixinWriter" : "MixinDescription";

		code.append("\t\tcom.dslplatform.json.runtime.").append(mixinType).append("<").append(className).append("> description = new com.dslplatform.json.runtime.").append(mixinType).append("<>(\n");
		code.append("\t\t\t").append(className).append(".class,\n");
		code.append("\t\t\tjson,\n");
		code.append("\t\t\tnew com.dslplatform.json.runtime.FormatDescription[] {\n");
		int i = si.implementations.size();
		for (StructInfo im : si.implementations) {
			if (im.formats.contains(CompiledJson.Format.OBJECT) && im.formats.contains(CompiledJson.Format.ARRAY)) {
				code.append("\t\t\t").append(im.name);
			} else {
				code.append("\t\t\t\tnew com.dslplatform.json.runtime.FormatDescription(");
				code.append(im.element.getQualifiedName()).append(".class, ");
				if (im.formats.contains(CompiledJson.Format.OBJECT)) {
					code.append("new ").append(findConverterName(im)).append(".ObjectFormatConverter(json), ");
				} else {
					code.append("null, ");
				}
				if (im.formats.contains(CompiledJson.Format.ARRAY)) {
					code.append("new ").append(findConverterName(im)).append(".ArrayFormatConverter(json), ");
				} else {
					code.append("null, ");
				}
				if (im.isObjectFormatFirst) code.append("true, ");
				else code.append("false, ");
				String typeAlias = im.deserializeName.isEmpty()
						? im.element.getQualifiedName().toString()
						: im.deserializeName;
				code.append("\"").append(typeAlias).append("\", json)");
			}
			i--;
			if (i > 0) code.append(",\n");
		}
		code.append("\n\t\t\t}\n");
		code.append("\t\t);\n");
		if (!writeOnly) {
			code.append("\t\tjson.registerReader(").append(className).append(".class, description);\n");
		}
		code.append("\t\tjson.registerWriter(").append(className).append(".class, description);\n");
	}

	private static boolean buildRootConfiguration(
			final Writer code,
			final String configurationName,
			final Map<String, StructInfo> configurations,
			final ProcessingEnvironment processingEnv) throws IOException {
		final int dotIndex = configurationName.lastIndexOf('.');
		final String generateClassName = configurationName.substring(dotIndex + 1);
		final boolean hasNamespace = dotIndex != -1;
		if (hasNamespace) {
			code.append("package ").append(configurationName, 0, dotIndex).append(";\n\n");
		}
		code.append("public class ").append(generateClassName).append(" implements com.dslplatform.json.Configuration {\n");
		code.append("\t@Override\n");
		code.append("\tpublic void configure(com.dslplatform.json.DslJson json) {\n");
		boolean allValid = true;
		for (Map.Entry<String, StructInfo> kv : configurations.entrySet()) {
			if (hasNamespace && kv.getKey().indexOf('.') == -1) {
				processingEnv.getMessager().printMessage(
						Diagnostic.Kind.ERROR,
						"Configuration file: '" + configurationName + "' is not in the root package, but referenced element does not have a package specified: '"
								+ kv.getValue().binaryName + "'. Use configuration name without package, eg: 'dsl_json_Annotation_Processor_External_Serialization' to allow access to specified class.",
						kv.getValue().element,
						kv.getValue().annotation);
				allValid = false;
			}
			code.append("\t\tnew ").append(kv.getKey()).append("().configure(json);\n");
		}
		code.append("\t}\n");
		code.append("}");
		return allValid;
	}
}
