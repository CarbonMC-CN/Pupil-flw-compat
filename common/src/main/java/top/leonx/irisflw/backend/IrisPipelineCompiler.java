package top.leonx.irisflw.backend;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.LightShader;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.MaterialShaders;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.Samplers;
import dev.engine_room.flywheel.backend.compile.ContextShader;
import dev.engine_room.flywheel.backend.compile.Pipeline;
import dev.engine_room.flywheel.backend.compile.PipelineCompiler;
import dev.engine_room.flywheel.backend.compile.component.InstanceStructComponent;
import dev.engine_room.flywheel.backend.compile.core.CompilationHarness;
import dev.engine_room.flywheel.backend.compile.core.Compile;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.gl.shader.ShaderType;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.ResourceLocation;
import top.leonx.irisflw.IrisFlw;
import top.leonx.irisflw.mixin.flw.PipelineCompilerAccessor;

import java.util.*;

public class IrisPipelineCompiler {
    private static final Set<IrisPipelineCompiler> ALL = Collections.newSetFromMap(new WeakHashMap<>());

    private static final Compile<PipelineProgramKey> PIPELINE = new Compile<>();

    private static final ResourceLocation API_IMPL_VERT = ResourceUtil.rl("internal/api_impl.vert");
    private static final ResourceLocation API_IMPL_FRAG = ResourceUtil.rl("internal/api_impl.frag");

    private final CompilationHarness<PipelineProgramKey> harness;

    public IrisPipelineCompiler(CompilationHarness<PipelineProgramKey> harness) {
        this.harness = harness;
        ALL.add(this);
    }

    public GlProgram get(InstanceType<?> instanceType, ContextShader contextShader, Material material, PipelineCompiler.OitMode oit, boolean isShadow) {
        var light = material.light();
        var cutout = material.cutout();
        var shaders = material.shaders();
        var fog = material.fog();

        // Tell fogSources to index the fog shader if we haven't seen it before.
        // If it is new, this will trigger a deletion of all programs.
        MaterialShaderIndices.fogSources()
                .index(fog.source());

        // Same thing for cutout.
        // Add OFF to the index here anyway to ensure MaterialEncoder doesn't deleteAll at an inappropriate time.
        MaterialShaderIndices.cutoutSources()
                .index(cutout.source());

        return harness.get(new PipelineProgramKey(instanceType, contextShader, light, shaders, cutout != CutoutShaders.OFF, FrameUniforms.debugOn(), oit, isShadow));
    }

    public void delete() {
        harness.delete();
    }

    public static void deleteAll() {
        ALL.forEach(IrisPipelineCompiler::delete);
    }

    static IrisPipelineCompiler create(ShaderSources sources, Pipeline pipeline, List<SourceComponent> vertexComponents, List<SourceComponent> fragmentComponents, Collection<String> extensions) {
        // We could technically compile every version of light smoothness ahead of time,
        // but that seems unnecessary as I doubt most folks will be changing this option often.
        var harnessStitcher = PIPELINE.program()
                .link(PIPELINE.shader(GlCompat.MAX_GLSL_VERSION, ShaderType.VERTEX)
                        .nameMapper(key -> {
                            var instance = ResourceUtil.toDebugFileNameNoExtension(key.instanceType()
                                    .vertexShader());

                            var material = ResourceUtil.toDebugFileNameNoExtension(key.materialShaders()
                                    .vertexSource());
                            var context = key.contextShader()
                                    .nameLowerCase();
                            var debug = key.debugEnabled() ? "_debug" : "";
                            return "pipeline/" + pipeline.compilerMarker() + "/" + instance + "/" + material + "_" + context + debug;
                        })
                        .requireExtensions(extensions)
                        .onCompile((rl, compilation) -> {
                            if (GlCompat.MAX_GLSL_VERSION.compareTo(GlslVersion.V400) < 0 && !extensions.contains("GL_ARB_gpu_shader5")) {
                                // Only define fma if it wouldn't be declared by gpu shader 5
                                compilation.define("fma(a, b, c) ((a) * (b) + (c))");
                            }
                        })
                        .onCompile((key, comp) -> key.contextShader()
                                .onCompile(comp))
                        .onCompile((key, comp) -> BackendConfig.INSTANCE.lightSmoothness()
                                .onCompile(comp))
                        .onCompile((key, comp) -> {
                            if (key.debugEnabled()) {
                                comp.define("_FLW_DEBUG");
                            }
                        })
                        .withResource(API_IMPL_VERT)
                        .withComponent(key -> new InstanceStructComponent(key.instanceType()))
                        .withResource(key -> key.instanceType()
                                .vertexShader())
                        .withResource(key -> key.materialShaders()
                                .vertexSource())
                        .withComponents(vertexComponents)
                        .withResource(($)->IrisFlw.isUsingExtendedVertexFormat() ? IrisInternalVertex.EXT_LAYOUT_SHADER : IrisInternalVertex.LAYOUT_SHADER)
                        .withComponent(key -> pipeline.assembler()
                                .assemble(key.instanceType()))
                        .withResource(pipeline.vertexMain()))
                .link(PIPELINE.shader(GlCompat.MAX_GLSL_VERSION, ShaderType.FRAGMENT)
                        .nameMapper(key -> {
                            var context = key.contextShader()
                                    .nameLowerCase();

                            var material = ResourceUtil.toDebugFileNameNoExtension(key.materialShaders()
                                    .fragmentSource());

                            var light = ResourceUtil.toDebugFileNameNoExtension(key.light()
                                    .source());
                            var debug = key.debugEnabled() ? "_debug" : "";
                            var cutout = key.useCutout() ? "_cutout" : "";
                            var oit = key.oit().name;
                            return "pipeline/" + pipeline.compilerMarker() + "/frag/" + material + "/" + light + "_" + context + cutout + debug + oit;
                        })
                        .requireExtensions(extensions)
                        .enableExtension("GL_ARB_conservative_depth")
                        .onCompile((rl, compilation) -> {
                            if (GlCompat.MAX_GLSL_VERSION.compareTo(GlslVersion.V400) < 0 && !extensions.contains("GL_ARB_gpu_shader5")) {
                                // Only define fma if it wouldn't be declared by gpu shader 5
                                compilation.define("fma(a, b, c) ((a) * (b) + (c))");
                            }
                        })
                        .onCompile((key, comp) -> key.contextShader()
                                .onCompile(comp))
                        .onCompile((key, comp) -> BackendConfig.INSTANCE.lightSmoothness()
                                .onCompile(comp))
                        .onCompile((key, comp) -> {
                            if (key.debugEnabled()) {
                                comp.define("_FLW_DEBUG");
                            }
                        })
                        .onCompile((key, comp) -> {
                            if (key.useCutout()) {
                                comp.define("_FLW_USE_DISCARD");
                            }
                        })
                        .onCompile((key, comp) -> {
                            if (key.oit() != PipelineCompiler.OitMode.OFF) {
                                comp.define("_FLW_OIT");
                                comp.define(key.oit().define);
                            }
                        })
                        .withResource(API_IMPL_FRAG)
                        .withResource(key -> key.materialShaders()
                                .fragmentSource())
                        .withComponents(fragmentComponents)
                        .withComponent(key -> PipelineCompilerAccessor.GetFOG())
                        .withResource(key -> key.light()
                                .source())
                        .with((key, fetcher) -> (key.useCutout() ? PipelineCompilerAccessor.GetCUTOUT() : fetcher.get(CutoutShaders.OFF.source())))
                        .withResource(pipeline.fragmentMain()))
                .preLink((key, program) -> {
                    program.bindAttribLocation("_flw_aPos", 0);
                    program.bindAttribLocation("_flw_aColor", 1);
                    program.bindAttribLocation("_flw_aTexCoord", 2);
                    program.bindAttribLocation("_flw_aOverlay", 3);
                    program.bindAttribLocation("_flw_aLight", 4);
                    program.bindAttribLocation("_flw_aNormal", 5);
                })
                .postLink((key, program) -> {
                    Uniforms.setUniformBlockBindings(program);

                    program.bind();

                    program.setSamplerBinding("flw_diffuseTex", Samplers.DIFFUSE);
                    program.setSamplerBinding("flw_overlayTex", Samplers.OVERLAY);
                    program.setSamplerBinding("flw_lightTex", Samplers.LIGHT);
                    program.setSamplerBinding("_flw_depthRange", Samplers.DEPTH_RANGE);
                    program.setSamplerBinding("_flw_coefficients", Samplers.COEFFICIENTS);
                    program.setSamplerBinding("_flw_blueNoise", Samplers.NOISE);
                    pipeline.onLink()
                            .accept(program);
                    key.contextShader()
                            .onLink(program);

                    GlProgram.unbind();
                });
                //.harness(pipeline.compilerMarker(), sources);

        var harness = new IrisCompilationHarness<>(pipeline.compilerMarker(), sources, harnessStitcher);
        return new IrisPipelineCompiler(harness);
    }

    public record PipelineProgramKey(InstanceType<?> instanceType, ContextShader contextShader, LightShader light, MaterialShaders materialShaders, boolean useCutout, boolean debugEnabled, PipelineCompiler.OitMode oit, boolean isShadow) {

        public InstanceType<?> instanceType() {
            return this.instanceType;
        }

        public ContextShader contextShader() {
            return this.contextShader;
        }

        public LightShader light() {
            return this.light;
        }

        public MaterialShaders materialShaders() {
            return this.materialShaders;
        }

        public boolean useCutout() {
            return this.useCutout;
        }

        public boolean debugEnabled() {
            return this.debugEnabled;
        }

        public PipelineCompiler.OitMode oit() {
            return this.oit;
        }
    }
}
