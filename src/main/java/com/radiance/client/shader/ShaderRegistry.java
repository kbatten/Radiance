package com.radiance.client.shader;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.radiance.client.RadianceClient;
import com.radiance.client.constant.Constants;
import com.radiance.client.proxy.vulkan.ShaderProxy;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IShaderProgramExt;
import net.minecraft.client.renderer.ShaderDefines;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public final class ShaderRegistry {

    private static final Pattern SAMPLER_UNIFORM_PATTERN = Pattern.compile(
        "^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+sampler\\w+\\s+(\\w+)\\s*;\\s*$");
    private static final Pattern SAMPLER_SLOT_PATTERN = Pattern.compile("\\bSampler(\\d+)\\b");

    private static final Map<GlProgram, ShaderDefinition> CACHE =
        Collections.synchronizedMap(new WeakHashMap<>());

    private ShaderRegistry() {
    }

    public static ShaderDefinition getOrCreate(GlProgram shaderProgram, ShaderDefines defines) {
        ShaderDefinition cached = CACHE.get(shaderProgram);
        if (cached != null) {
            return cached;
        }

        ShaderDefinition created = create(shaderProgram, defines);
        CACHE.put(shaderProgram, created);
        return created;
    }

    public static void clear() {
        CACHE.clear();
    }

    private static ShaderDefinition create(GlProgram shaderProgram, ShaderDefines defines) {
        IShaderProgramExt ext = (IShaderProgramExt) (Object) shaderProgram;
        VertexFormat vertexFormat = ext.radiance$getVertexFormat();
        String vertexSource = ext.radiance$getVertexSource();
        String fragmentSource = ext.radiance$getFragmentSource();
        String shaderName = ext.radiance$getShaderName();
        if (vertexFormat == null || vertexSource == null || fragmentSource == null
            || shaderName == null) {
            throw new IllegalStateException("Missing shader metadata for dynamic registration");
        }
        List<ShaderField> fields = buildFields(vertexSource, fragmentSource);

        ShaderTranslator.Result result = ShaderTranslator.translate(vertexFormat, vertexSource,
            fragmentSource, fields);

        // The pipeline's ShaderDefines drive the #if branches in the captured source (e.g. IS_GUI in
        // core/text excludes the UV2 attribute and the fog varyings). MC compiles the shader with
        // these defined; the mod captures the pre-injection source, so they must be handed to the
        // native compiler or the wrong branch is compiled -- for gui_text that left "in ivec2 UV2;"
        // with no matching vertex attribute, which fails as "SPIR-V requires location". Split the
        // record's value defines (name -> value) and bare flags (name, no value) into the parallel
        // name/value arrays registerShader forwards to shaderc's AddMacroDefinition.
        String[] defineNames = new String[defines.values().size() + defines.flags().size()];
        String[] defineValues = new String[defineNames.length];
        int defineCount = 0;
        for (Map.Entry<String, String> value : defines.values().entrySet()) {
            defineNames[defineCount] = value.getKey();
            defineValues[defineCount] = value.getValue();
            defineCount++;
        }
        for (String flag : defines.flags()) {
            defineNames[defineCount] = flag;
            defineValues[defineCount] = "";
            defineCount++;
        }

        String key = buildKey(shaderName, vertexFormat, result.vertexSource(),
            result.fragmentSource(), fields, defines);
        Path directory = getShaderDirectory();
        Path vertexPath = directory.resolve(key + ".vert");
        Path fragmentPath = directory.resolve(key + ".frag");
        writeIfChanged(vertexPath, result.vertexSource());
        writeIfChanged(fragmentPath, result.fragmentSource());

        int nativeId = ShaderProxy.registerShader(key,
            Constants.VertexFormats.getValue(vertexFormat),
            Constants.DrawModes.QUADS.getValue(),
            result.uniformBufferSize(),
            vertexPath.toString(),
            fragmentPath.toString(),
            defineNames,
            defineValues);
        return new ShaderDefinition(key, shaderName, nativeId, result.uniformBufferSize(), fields);
    }

    private static List<ShaderField> buildFields(String vertexSource, String fragmentSource) {
        ArrayList<ShaderField> fields = new ArrayList<>();
        int offset = 0;

        // 26.2: the uniform list came from the program's GlUniforms; those are gone. Instead take
        // the built-in UBO members the shader references (BuiltinUniforms carries each one's kind /
        // component count / source UBO+offset), laid out in the native blob in declaration order.
        String combinedSource = vertexSource + "\n" + fragmentSource;
        for (BuiltinUniforms.Entry entry : BuiltinUniforms.all()) {
            if (!referencesName(combinedSource, entry.name())) {
                continue;
            }
            ShaderField.Kind kind = entry.kind();
            int componentCount = entry.componentCount();
            int alignment = getAlignment(kind, componentCount);
            int size = getSize(kind, componentCount);
            offset = align(offset, alignment);
            fields.add(new ShaderField(entry.name(), entry.name(), kind, componentCount, offset,
                size, -1));
            offset += size;
        }

        List<String> resolvedSamplerNames = resolveSamplerNames(List.of(), vertexSource,
            fragmentSource);
        for (int i = 0; i < resolvedSamplerNames.size(); i++) {
            String samplerName = resolvedSamplerNames.get(i);
            offset = align(offset, Integer.BYTES);
            fields.add(new ShaderField(samplerName, samplerName + "Index",
                ShaderField.Kind.SAMPLER, 1, offset, Integer.BYTES,
                getSamplerSlot(samplerName, i)));
            offset += Integer.BYTES;
        }

        return List.copyOf(fields);
    }

    private static List<String> resolveSamplerNames(List<String> declaredSamplerNames,
        String vertexSource, String fragmentSource) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>(declaredSamplerNames);
        collectSamplerNames(vertexSource, resolved);
        collectSamplerNames(fragmentSource, resolved);
        return List.copyOf(resolved);
    }

    private static void collectSamplerNames(String source, LinkedHashSet<String> names) {
        for (String line : source.split("\\R", -1)) {
            Matcher uniformMatcher = SAMPLER_UNIFORM_PATTERN.matcher(line);
            if (uniformMatcher.matches()) {
                names.add(uniformMatcher.group(1));
            }

            Matcher samplerMatcher = SAMPLER_SLOT_PATTERN.matcher(line);
            while (samplerMatcher.find()) {
                names.add("Sampler" + samplerMatcher.group(1));
            }
        }
    }

    private static int getSamplerSlot(String samplerName, int fallbackSlot) {
        Matcher matcher = SAMPLER_SLOT_PATTERN.matcher(samplerName);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        return fallbackSlot;
    }

    private static boolean referencesName(String source, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source).find();
    }

    private static int getAlignment(ShaderField.Kind kind, int componentCount) {
        return switch (kind) {
            case SAMPLER -> Integer.BYTES;
            case INT, FLOAT -> switch (componentCount) {
                case 1 -> Integer.BYTES;
                case 2 -> Integer.BYTES * 2;
                case 3, 4 -> Integer.BYTES * 4;
                default -> throw new IllegalStateException(
                    "Unsupported component count: " + componentCount);
            };
            case MATRIX -> Integer.BYTES * 4;
        };
    }

    private static int getSize(ShaderField.Kind kind, int componentCount) {
        return switch (kind) {
            case SAMPLER -> Integer.BYTES;
            case INT, FLOAT -> switch (componentCount) {
                case 1 -> Integer.BYTES;
                case 2 -> Integer.BYTES * 2;
                case 3, 4 -> Integer.BYTES * 4;
                default -> throw new IllegalStateException(
                    "Unsupported component count: " + componentCount);
            };
            case MATRIX -> Integer.BYTES * 4 * componentCount;
        };
    }

    private static int align(int value, int alignment) {
        return Math.ceilDiv(value, alignment) * alignment;
    }

    private static String buildKey(String shaderName, VertexFormat vertexFormat, String vertexSource,
        String fragmentSource, List<ShaderField> fields, ShaderDefines defines) {
        // Defines are part of the identity: the translated source is byte-identical across variants
        // of one shader (the translator does not evaluate #if -- the native compiler does with these
        // defines), so without them two variants like gui_text and gui_text_grayscale would hash to
        // the same key, collide on the same .vert/.frag files and native shader id, and one would be
        // compiled with the other's defines.
        StringBuilder builder = new StringBuilder(shaderName).append('\n')
            .append(vertexFormat)
            .append('\n')
            .append(vertexSource)
            .append('\n')
            .append(fragmentSource)
            .append('\n')
            .append(defines.asSourceDirectives())
            .append('\n');
        for (ShaderField field : fields) {
            builder.append(field.name())
                .append(':')
                .append(field.kind())
                .append(':')
                .append(field.componentCount())
                .append(':')
                .append(field.offset())
                .append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(builder.toString()
                .getBytes(StandardCharsets.UTF_8));
            return shaderName.replaceAll("[^a-zA-Z0-9._-]", "_")
                + "-"
                + HexFormat.of()
                .formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static Path getShaderDirectory() {
        Path directory = RadianceClient.radianceDir.resolve("temp")
            .resolve("shaders")
            .resolve("overlay");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create shader directory", e);
        }
        return directory;
    }

    private static void writeIfChanged(Path path, String content) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(path)) {
                String existing = Files.readString(path);
                if (existing.equals(content)) {
                    return;
                }
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write shader file: " + path, e);
        }
    }
}
