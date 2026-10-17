/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.compress.v3.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodHandles.throwException;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

/**
 * Binds the functions of a native library installed on the system through the foreign function API.
 * Nothing is bundled with this library and nothing is written to disk: the library is looked up by name
 * the way the dynamic linker does ({@code dlopen}), and if it cannot be found, or is not wanted, every
 * bound handle throws the {@link LinkageError} that explains why, so that callers can fall back to Java.
 * <p>
 * Each library is controlled by a system property, {@code <property>} below, with the values
 * {@code auto} (the default), {@code java} and {@code native}:
 * <ul>
 * <li>{@code auto} uses the native library when it is installed <em>and</em> native access is enabled
 * for this module ({@code --enable-native-access=io.airlift.compress.v3}, or {@code ALL-UNNAMED} on the
 * class path). Without that flag the JDK prints a warning on the first restricted call, which a library
 * must not cause on its own, so Java is used instead, silently.</li>
 * <li>{@code java} never loads the native library.</li>
 * <li>{@code native} always tries to, warning included; using a native class then fails with
 * {@link IllegalStateException} when the library is missing.</li>
 * </ul>
 * {@code <property>.library} names the library file explicitly (a path, or a name for the dynamic linker).
 */
public final class NativeLoader
{
    private static final MethodHandle LINKAGE_ERROR_CONSTRUCTOR;

    static {
        try {
            LINKAGE_ERROR_CONSTRUCTOR = lookup().findConstructor(LinkageError.class, methodType(void.class, String.class, Throwable.class));
        }
        catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private NativeLoader() {}

    public enum Mode
    {
        AUTO, JAVA, NATIVE;

        public static Mode of(String property)
        {
            String value = System.getProperty(property, "auto").trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "auto" -> AUTO;
                case "java" -> JAVA;
                case "native" -> NATIVE;
                default -> throw new IllegalArgumentException("Unknown value '%s' for system property %s, expected auto, java or native".formatted(value, property));
            };
        }
    }

    public record Symbols<T>(Optional<LinkageError> linkageError, T symbols) {}

    /**
     * @param property the system property selecting the mode, see the class comment
     * @param libraryNames the names to try, in order, when {@code <property>.library} is not set, for example
     * {@code libzstd.so.1} (a Linux distribution usually installs only the versioned name) and {@code libzstd.so}
     * @param methodHandlesClass a record whose components are {@link MethodHandle}s annotated with {@link NativeSignature}
     */
    public static <T> Symbols<T> loadSymbols(String property, List<String> libraryNames, Class<T> methodHandlesClass, MethodHandles.Lookup lookup)
    {
        requireNonNull(property, "property is null");
        requireNonNull(libraryNames, "libraryNames is null");
        requireNonNull(methodHandlesClass, "methodHandlesClass is null");
        if (!methodHandlesClass.isRecord()) {
            throw new IllegalArgumentException("methodHandlesClass is not a record class");
        }
        for (RecordComponent recordComponent : methodHandlesClass.getRecordComponents()) {
            if (recordComponent.getAnnotation(NativeSignature.class) == null) {
                throw new IllegalArgumentException("methodHandlesClass %s field '%s' is missing @NativeSignature annotation".formatted(methodHandlesClass, recordComponent.getName()));
            }
        }

        MethodHandle constructor;
        try {
            constructor = lookup.findConstructor(methodHandlesClass, methodType(void.class, Arrays.stream(methodHandlesClass.getRecordComponents())
                    .map(RecordComponent::getType)
                    .toArray(Class<?>[]::new)));
        }
        catch (IllegalAccessException | NoSuchMethodException e) {
            throw new IllegalArgumentException("Failed to find canonical constructor for " + methodHandlesClass, e);
        }

        try {
            try {
                SymbolLookup symbolLookup = loadLibrary(property, libraryNames);
                List<MethodHandle> methodHandles = new ArrayList<>(methodHandlesClass.getRecordComponents().length);
                for (RecordComponent recordComponent : methodHandlesClass.getRecordComponents()) {
                    NativeSignature nativeSignature = recordComponent.getAnnotation(NativeSignature.class);

                    FunctionDescriptor nativeFunctionDescriptor = getFunctionDescriptor(nativeSignature.returnType(), nativeSignature.argumentTypes());

                    // critical: the calls are short and never call back into Java, and heap segments (byte[]) are
                    // passed without copying
                    methodHandles.add(symbolLookup.find(nativeSignature.name())
                            .map(memorySegment -> Linker.nativeLinker().downcallHandle(memorySegment, nativeFunctionDescriptor, Linker.Option.critical(true)))
                            .orElseThrow(() -> new LinkageError("unresolved symbol: " + nativeSignature.name())));
                }
                return new Symbols<>(Optional.empty(), methodHandlesClass.cast(constructor.invokeWithArguments(methodHandles)));
            }
            catch (LinkageError e) {
                List<MethodHandle> methodHandles = new ArrayList<>(methodHandlesClass.getRecordComponents().length);
                for (RecordComponent recordComponent : methodHandlesClass.getRecordComponents()) {
                    NativeSignature nativeSignature = recordComponent.getAnnotation(NativeSignature.class);
                    FunctionDescriptor nativeFunctionDescriptor = getFunctionDescriptor(nativeSignature.returnType(), nativeSignature.argumentTypes());
                    methodHandles.add(createErrorMethodHandle(libraryNames.getFirst(), e, nativeFunctionDescriptor.toMethodType()));
                }
                return new Symbols<>(Optional.of(e), methodHandlesClass.cast(constructor.invokeWithArguments(methodHandles)));
            }
        }
        catch (Throwable e) {
            throw new RuntimeException("Failed to create instance of " + methodHandlesClass, e);
        }
    }

    private static FunctionDescriptor getFunctionDescriptor(Class<?> returnType, Class<?>[] argumentTypes)
    {
        ValueLayout[] argumentLayouts = Arrays.stream(argumentTypes)
                .map(NativeLoader::getMemoryLayout)
                .toArray(ValueLayout[]::new);
        if (returnType == void.class) {
            return FunctionDescriptor.ofVoid(argumentLayouts);
        }
        return FunctionDescriptor.of(
                getMemoryLayout(returnType),
                argumentLayouts);
    }

    private static MethodHandle createErrorMethodHandle(String name, LinkageError linkageError, MethodType methodType)
    {
        MethodHandle methodHandle = throwException(methodType.returnType(), LinkageError.class);
        return MethodHandles.foldArguments(methodHandle, insertArguments(LINKAGE_ERROR_CONSTRUCTOR, 0, name + " native library not loaded: " + linkageError.getMessage(), linkageError));
    }

    private static ValueLayout getMemoryLayout(Class<?> type)
    {
        if (type == byte.class) {
            return JAVA_BYTE;
        }
        if (type == int.class) {
            return JAVA_INT;
        }
        if (type == long.class) {
            return JAVA_LONG;
        }
        if (type == MemorySegment.class) {
            return ADDRESS;
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }

    /** The names the dynamic linker knows a library by on this platform, versioned name first. */
    public static List<String> platformLibraryNames(String name, int majorVersion)
    {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of("lib" + name + "." + majorVersion + ".dylib", "lib" + name + ".dylib");
        }
        if (os.contains("win")) {
            return List.of("lib" + name + ".dll", name + ".dll");
        }
        return List.of("lib" + name + ".so." + majorVersion, "lib" + name + ".so");
    }

    public static SymbolLookup loadLibrary(String property, List<String> libraryNames)
            throws LinkageError
    {
        Mode mode = Mode.of(property);
        if (mode == Mode.JAVA) {
            throw new LinkageError("disabled by -D" + property + "=java");
        }
        Module module = NativeLoader.class.getModule();
        if (mode == Mode.AUTO && !module.isNativeAccessEnabled()) {
            String enable = module.isNamed() ? module.getName() : "ALL-UNNAMED";
            throw new LinkageError("native access is not enabled: run with --enable-native-access=" + enable + ", or set -D" + property + "=native");
        }

        String explicit = System.getProperty(property + ".library");
        List<String> names = explicit != null ? List.of(explicit) : libraryNames;
        List<String> failures = new ArrayList<>();
        for (String name : names) {
            try {
                if (name.contains("/") || name.contains("\\")) {
                    return SymbolLookup.libraryLookup(Path.of(name), Arena.ofAuto());
                }
                return SymbolLookup.libraryLookup(name, Arena.ofAuto());
            }
            catch (IllegalArgumentException e) {
                failures.add(name + " (" + e.getMessage() + ")");
            }
        }
        throw new LinkageError("library not found: " + String.join(", ", failures));
    }
}
