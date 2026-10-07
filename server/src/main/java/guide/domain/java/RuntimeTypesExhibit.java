package guide.domain.java;

import guide.content.TextBlock;
import guide.content.Topic;
import guide.core.result.Result;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The live exhibit behind topic 01's runtime-types demo: pure reflection
 * over this repo's OWN classes. Two questions, two answers:
 * - What can reflection see? Record component names + declared types,
 *   sealed permits, class annotations — the metadata frameworks lean on.
 * - What can it NOT see? The parameterization of a generic INSTANCE: two
 *   differently-parameterized holders share one raw Class at runtime,
 *   even though the DECLARED generic types of fields survive in the class
 *   file and are readable via getGenericType().
 *
 * Pure domain logic: no Spring, no I/O. The whitelist below is deliberately
 * tiny — reflection endpoints must never accept arbitrary class names
 * (classloading by user input is a security hole).
 */
public final class RuntimeTypesExhibit {

    /** The inspectable classes, by the short name the API accepts. */
    public static final List<String> INSPECTABLE = List.of("result", "text-block", "topic");

    private static final Map<String, Class<?>> WHITELIST = whitelist();

    private static Map<String, Class<?>> whitelist() {
        Map<String, Class<?>> map = new HashMap<>();
        map.put("result", Result.class);
        map.put("text-block", TextBlock.class);
        map.put("topic", Topic.class);
        return Map.copyOf(map);
    }

    /** Generic holder: the erasure subject — instances forget their T. */
    public record Box<T>(List<T> items) {
    }

    /** Declared generic fields DO survive in the class file's signature. */
    public static final class Catalog {

        private final List<String> tags = new ArrayList<>();
        private final Map<String, Integer> counts = new HashMap<>();

        private Catalog() {
        }
    }

    /** One record component: its name and declared (possibly generic) type. */
    public record ComponentView(String name, String type) {
    }

    /** Reflection report for one whitelisted class. */
    public record ClassReport(String requestedName, String className, String kind,
            List<ComponentView> recordComponents, boolean sealed, List<String> permittedSubclasses,
            List<String> annotations) {

        public ClassReport {
            recordComponents = List.copyOf(recordComponents);
            permittedSubclasses = List.copyOf(permittedSubclasses);
            annotations = List.copyOf(annotations);
        }
    }

    /** One declared field: the generic signature vs the erased raw type. */
    public record FieldView(String name, String genericType, String rawType) {
    }

    /** One parameterized Box instance: what its class says at runtime. */
    public record BoxInstanceView(String declaredAs, String rawClass, List<String> items) {
    }

    /** Side-by-side erasure evidence: declarations keep generics, instances do not. */
    public record ErasureReport(List<FieldView> declaredFields, List<BoxInstanceView> instances,
            boolean shareOneRawClass) {

        public ErasureReport {
            declaredFields = List.copyOf(declaredFields);
            instances = List.copyOf(instances);
        }
    }

    /** Reflects one whitelisted class; empty when the short name is unknown. */
    public Optional<ClassReport> inspect(String shortName) {
        Class<?> matched = shortName == null
                ? null
                : WHITELIST.get(shortName.trim().toLowerCase(Locale.ROOT));
        if (matched == null) {
            return Optional.empty();
        }

        List<ComponentView> components = new ArrayList<>();
        RecordComponent[] declared = matched.getRecordComponents();
        if (declared != null) {
            for (RecordComponent component : declared) {
                components.add(new ComponentView(component.getName(),
                        component.getGenericType().getTypeName()));
            }
        }

        List<String> permitted = new ArrayList<>();
        if (matched.isSealed()) {
            for (Class<?> subtype : matched.getPermittedSubclasses()) {
                permitted.add(subtype.getCanonicalName());
            }
        }

        List<String> annotations = new ArrayList<>();
        for (Annotation annotation : matched.getAnnotations()) {
            annotations.add(annotation.annotationType().getCanonicalName());
        }

        return Optional.of(new ClassReport(shortName, matched.getCanonicalName(), kindOf(matched),
                components, matched.isSealed(), permitted, annotations));
    }

    /** Builds the erasure evidence: declared generic fields vs two erased instances. */
    public ErasureReport erasureEvidence() {
        List<FieldView> fields = new ArrayList<>();
        for (Field field : Catalog.class.getDeclaredFields()) {
            fields.add(new FieldView(field.getName(), field.getGenericType().getTypeName(),
                    field.getType().getTypeName()));
        }

        Box<String> strings = new Box<>(List.of("guide", "boot", "jvm"));
        Box<Integer> numbers = new Box<>(List.of(1, 2, 3));
        boolean erased = strings.getClass() == numbers.getClass();
        List<BoxInstanceView> instances = List.of(
                new BoxInstanceView("Box<String>", strings.getClass().getCanonicalName(),
                        strings.items().stream().map(Object::toString).toList()),
                new BoxInstanceView("Box<Integer>", numbers.getClass().getCanonicalName(),
                        numbers.items().stream().map(Object::toString).toList()));
        return new ErasureReport(fields, instances, erased);
    }

    private static String kindOf(Class<?> cls) {
        if (cls.isRecord()) {
            return "record";
        }
        if (cls.isEnum()) {
            return "enum";
        }
        if (cls.isSealed()) {
            return "sealed interface";
        }
        if (cls.isInterface()) {
            return "interface";
        }
        return "class";
    }
}
