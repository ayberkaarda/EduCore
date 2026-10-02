package com.educore.architecture;

import com.educore.auth.LoginAttempt;
import com.educore.auth.RefreshToken;
import com.educore.course.CourseResponse;
import com.educore.entity.Account;
import com.educore.entity.Course;
import com.educore.security.audit.SecurityEvent;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.MappedSuperclass;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture rules of the web layer, query construction and logging hygiene, checked on the compiled main
 * classes (test classes excluded).
 */
class ArchitectureTest {

    private static final String ENTITY_PACKAGE = "com.educore.entity";

    /**
     * Ingestion classes that still write to {@code System.out}/{@code System.err} or call
     * {@code printStackTrace}. They are exempt from the standard-stream rules until the logging clean-up
     * converts them to SLF4J; {@link #pendingLoggingCleanupListIsExact()} fails as soon as one of them is
     * clean, so the entry must then be removed and the list can only shrink.
     */
    static final List<Class<?>> PENDING_LOGGING_CLEANUP = List.of();

    /**
     * Main classes only. ArchUnit's {@code DO_NOT_INCLUDE_TESTS} only knows the default {@code target/} layout,
     * so any {@code test-classes} directory is excluded explicitly (the build directory is configurable via
     * {@code -DbuildDirName}).
     */
    private static final ImportOption MAIN_CLASSES_ONLY = location ->
            !location.contains("/test-classes/");

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(MAIN_CLASSES_ONLY)
            .importPackages("com.educore");

    private static final DescribedPredicate<JavaClass> PENDING =
            belongToAnyOf(PENDING_LOGGING_CLEANUP.toArray(new Class<?>[0]));

    // ---- web layer -----------------------------------------------------------------------------------------

    @Test
    void controllerSignaturesUseNoEntityTypes() {
        entityExposureRule().check(MAIN);
    }

    /**
     * The rule finds persistent types in every package (anything annotated {@code @Entity},
     * {@code @Embeddable} or {@code @MappedSuperclass}, plus the entity package) and inside generic arguments.
     */
    @Test
    void entityExposureRuleDetectsEntitiesInAnyPackageAndInsideGenerics() {
        JavaClasses exposing = new ClassFileImporter().importClasses(EntityFixtures.ExposingController.class,
                RefreshToken.class, LoginAttempt.class, SecurityEvent.class, Account.class, Course.class);
        JavaClasses clean = new ClassFileImporter().importClasses(EntityFixtures.CleanController.class,
                CourseResponse.class);

        assertThatThrownBy(() -> entityExposureRule().check(exposing))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("tokens()").hasMessageContaining(RefreshToken.class.getName())
                .hasMessageContaining("events()").hasMessageContaining(SecurityEvent.class.getName())
                .hasMessageContaining("attempt()").hasMessageContaining(LoginAttempt.class.getName())
                .hasMessageContaining("update(").hasMessageContaining(Account.class.getName())
                .hasMessageContaining("nested()").hasMessageContaining(Course.class.getName());
        entityExposureRule().check(clean);
    }

    @Test
    void controllersDoNotAccessRepositories() {
        ArchRule rule = noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat(assignableTo(org.springframework.data.repository.Repository.class))
                .orShould().dependOnClassesThat(assignableTo(EntityManager.class))
                .because("controllers go through a service (Controller -> Service -> Repository)");

        rule.check(MAIN);
    }

    @Test
    void featureControllersValidateTheirParameters() {
        ArchRule rule = classes().that().areAnnotatedWith(RestController.class)
                .and(DescribedPredicate.not(assignableTo(ErrorController.class)))
                .should().beAnnotatedWith(Validated.class)
                .because("path and query parameter constraints are enforced by method validation");

        rule.check(MAIN);
    }

    // ---- queries -------------------------------------------------------------------------------------------

    /**
     * Constant provenance at every query sink. For each call of a query-creating method whose first parameter
     * is the query text ({@code EntityManager.createQuery/createNativeQuery}, Hibernate
     * {@code createQuery/createNativeQuery/createSelectionQuery/createMutationQuery}, {@code JdbcTemplate} /
     * {@code NamedParameterJdbcTemplate} {@code query*}/{@code update}/{@code batchUpdate}/{@code execute},
     * {@code JdbcClient.sql}), the bytecode producing that argument must be
     * <ul>
     *   <li>a string constant ({@code ldc}; javac folds literal and {@code static final} constant
     *       concatenation into one constant), or</li>
     *   <li>a local variable that is not a method parameter and whose every assignment is itself constant
     *       (directly or through the {@code goto} branches of a conditional expression).</li>
     * </ul>
     * Anything else (a parameter, a method call such as a helper that builds SQL, a field, a concatenation, a
     * cast) is a violation, so SQL cannot be built in one method and executed in another. The query-text
     * argument is located by tracking the operand stack depth, so the other arguments (varargs arrays,
     * method calls) may be arbitrary expressions.
     * <p>
     * Residual blind spots: calls through reflection or method handles; query methods reached through a
     * static type that is not listed (another wrapper library, a subinterface such as
     * {@code SessionImplementor}); query text that is constant but assembled from untrusted data at build
     * time; Spring Data {@code @Query} texts with SpEL ({@code :#{...}}) expressions (annotation values are
     * always constants, the expressions are not checked); Criteria API literals. Spring Data derived queries
     * and {@code @Query} annotations bind parameters and are outside this rule.
     */
    @Test
    void queryTextHasConstantProvenance() {
        classes().should(createQueriesFromConstantsOnly()).check(MAIN);
    }

    @Test
    void queryRuleRejectsBuiltOrPassedInTextAndAcceptsConstants() {
        Map<Class<?>, String> rejected = Map.of(
                QueryFixtures.Concatenating.class, "byName",
                QueryFixtures.Formatting.class, "ordered",
                QueryFixtures.HelperBuilt.class, "viaHelper",
                QueryFixtures.ParameterPassed.class, "run",
                QueryFixtures.ConditionalConcatenation.class, "pick",
                QueryFixtures.LocalReassignedFromParameter.class, "reassigned",
                QueryFixtures.FieldText.class, "fromField",
                QueryFixtures.JdbcBuiltWithVarargs.class, "claim",
                QueryFixtures.ConstantTransformed.class, "filled");
        rejected.forEach((fixture, method) -> assertThatThrownBy(
                () -> classes().should(createQueriesFromConstantsOnly()).check(new ClassFileImporter().importClasses(fixture)))
                .as(fixture.getSimpleName())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("." + method + " "));

        for (Class<?> fixture : List.of(QueryFixtures.Constant.class, QueryFixtures.ConstantWithResultClass.class,
                QueryFixtures.StaticConstant.class, QueryFixtures.JdbcConstantWithVarargs.class)) {
            classes().should(createQueriesFromConstantsOnly()).check(new ClassFileImporter().importClasses(fixture));
        }
    }

    // ---- logging hygiene -----------------------------------------------------------------------------------

    @Test
    void noClassesAccessStandardStreams() {
        NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(MAIN.that(DescribedPredicate.not(PENDING)));
    }

    @Test
    void noClassesPrintStackTraces() {
        noClasses().should().callMethodWhere(new DescribedPredicate<>("Throwable.printStackTrace") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaMethodCall call) {
                        return call.getTarget().getName().equals("printStackTrace")
                                && call.getTargetOwner().isAssignableTo(Throwable.class);
                    }
                })
                .because("exceptions are logged through SLF4J with the request id")
                .check(MAIN.that(DescribedPredicate.not(PENDING)));
    }

    @Test
    void pendingLoggingCleanupListIsExact() {
        for (Class<?> pending : PENDING_LOGGING_CLEANUP) {
            JavaClasses only = MAIN.that(belongToAnyOf(pending));
            assertThat(only).as("%s is imported", pending.getName()).isNotEmpty();
            assertThatThrownBy(() -> NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(only))
                    .as("%s no longer uses System.out/err or printStackTrace: remove it from PENDING_LOGGING_CLEANUP",
                            pending.getName())
                    .isInstanceOf(AssertionError.class);
        }
    }

    // ---- rules and conditions ------------------------------------------------------------------------------

    private static ArchRule entityExposureRule() {
        return methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .and().arePublic()
                .should(notExposePersistentTypes())
                .because("controllers accept and return DTO records only; entities never cross the web boundary");
    }

    /**
     * {@code @Entity}, {@code @Embeddable} or {@code @MappedSuperclass} in any package, or any non-enum class of
     * the entity package. Enums there ({@code Role}, {@code JobLogStatus}) are immutable value types that bind
     * request parameters and are allowed.
     */
    private static boolean isPersistentType(JavaClass type) {
        if (type.isAnnotatedWith(Entity.class) || type.isAnnotatedWith(Embeddable.class)
                || type.isAnnotatedWith(MappedSuperclass.class)) {
            return true;
        }
        boolean inEntityPackage = type.getPackageName().equals(ENTITY_PACKAGE)
                || type.getPackageName().startsWith(ENTITY_PACKAGE + ".");
        return inEntityPackage && !type.isEnum();
    }

    private static ArchCondition<JavaMethod> notExposePersistentTypes() {
        return new ArchCondition<>("not use persistent types (also as generic arguments) as parameter or return type") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                List<JavaType> types = new ArrayList<>(method.getParameterTypes());
                types.add(method.getReturnType());
                for (JavaType type : types) {
                    for (JavaClass involved : type.getAllInvolvedRawTypes()) {
                        if (isPersistentType(involved)) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " exposes " + involved.getName()));
                        }
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> createQueriesFromConstantsOnly() {
        return new ArchCondition<>("create JPA/JDBC queries from constant text only") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (javaClass.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                    return;
                }
                for (String violation : QueryBytecode.nonConstantQuerySinks(javaClass.reflect())) {
                    events.add(SimpleConditionEvent.violated(javaClass, javaClass.getName() + "." + violation));
                }
            }
        };
    }

    /**
     * Bytecode analysis with the ASM copy shipped in spring-core. A forward pass records the operand stack
     * depth before every instruction; the producer of a value at stack position {@code p} is then the last
     * instruction before the consumer that starts at depth {@code <= p}. Merge points (labels that are jump
     * targets) are followed into every predecessor, so both branches of a conditional expression are checked.
     */
    static final class QueryBytecode {

        private static final Set<String> QUERY_OWNERS = Set.of(
                "jakarta/persistence/EntityManager", "org/hibernate/Session", "org/hibernate/SharedSessionContract",
                "org/hibernate/StatelessSession", "org/hibernate/query/QueryProducer",
                "org/springframework/jdbc/core/JdbcTemplate", "org/springframework/jdbc/core/JdbcOperations",
                "org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate",
                "org/springframework/jdbc/core/namedparam/NamedParameterJdbcOperations",
                "org/springframework/jdbc/core/simple/JdbcClient");
        private static final Set<String> QUERY_METHODS = Set.of(
                "createQuery", "createNativeQuery", "createSelectionQuery", "createMutationQuery",
                "createNativeMutationQuery", "query", "queryForObject", "queryForList", "queryForMap",
                "queryForRowSet", "queryForStream", "update", "batchUpdate", "execute", "sql");
        private static final int LABEL = -1;
        private static final int UNKNOWN = Integer.MIN_VALUE;

        private QueryBytecode() {
        }

        /** {@code method sink} descriptions of every query sink whose text is not provably constant. */
        static List<String> nonConstantQuerySinks(Class<?> type) {
            String resource = type.getName().replace('.', '/') + ".class";
            ClassLoader loader = type.getClassLoader() != null ? type.getClassLoader() : ClassLoader.getSystemClassLoader();
            try (InputStream bytes = loader.getResourceAsStream(resource)) {
                if (bytes == null) {
                    throw new IllegalStateException("Class file not found: " + resource);
                }
                List<String> violations = new ArrayList<>();
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                     String[] exceptions) {
                        int parameterSlots = (Type.getArgumentsAndReturnSizes(descriptor) >> 2)
                                - ((access & Opcodes.ACC_STATIC) != 0 ? 1 : 0);
                        return new MethodAnalyzer(name, parameterSlots, violations);
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return violations;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + resource, e);
            }
        }

        /**
         * One instruction (or a label, {@code opcode == LABEL}) with the stack depth before it and the lowest
         * stack position it reads or replaces ({@code depthBefore - pops}).
         */
        private record Insn(int opcode, Object operand, int depthBefore, int lowestTouched) {
        }

        private record Sink(String owner, String method, int argumentSlots) {
        }

        private static final class MethodAnalyzer extends MethodVisitor {

            private final String name;
            private final int parameterSlots;
            private final List<String> violations;
            private final List<Insn> insns = new ArrayList<>();
            private final Set<Label> handlerLabels = new HashSet<>();
            private final Map<Label, Integer> jumpDepths = new java.util.HashMap<>();
            private final List<Integer> sinks = new ArrayList<>();
            private int depth;
            private boolean flowEnded;
            private boolean consistent = true;

            MethodAnalyzer(String name, int parameterSlots, List<String> violations) {
                super(Opcodes.ASM9);
                this.name = name;
                this.parameterSlots = parameterSlots;
                this.violations = violations;
            }

            private void add(int opcode, Object operand, int effect, int pops) {
                insns.add(new Insn(opcode, operand, depth, depth - pops));
                depth += effect;
                if (depth < 0) {
                    consistent = false;
                    depth = 0;
                }
                flowEnded = opcode == Opcodes.GOTO || opcode == Opcodes.ATHROW || opcode == Opcodes.TABLESWITCH
                        || opcode == Opcodes.LOOKUPSWITCH || (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN);
            }

            private void jumpTo(Label target, int depthAtTarget) {
                jumpDepths.putIfAbsent(target, depthAtTarget);
            }

            @Override
            public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                handlerLabels.add(handler);
            }

            @Override
            public void visitLabel(Label label) {
                if (handlerLabels.contains(label)) {
                    depth = 1;
                } else if (flowEnded && jumpDepths.containsKey(label)) {
                    depth = jumpDepths.get(label);
                } else if (flowEnded) {
                    depth = 0;
                }
                insns.add(new Insn(LABEL, label, depth, depth));
                flowEnded = false;
            }

            @Override
            public void visitInsn(int opcode) {
                add(opcode, null, simpleEffect(opcode), simplePops(opcode));
            }

            @Override
            public void visitIntInsn(int opcode, int operand) {
                add(opcode, operand, opcode == Opcodes.NEWARRAY ? 0 : 1, opcode == Opcodes.NEWARRAY ? 1 : 0);
            }

            @Override
            public void visitVarInsn(int opcode, int varIndex) {
                int effect = switch (opcode) {
                    case Opcodes.LLOAD, Opcodes.DLOAD -> 2;
                    case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> 1;
                    case Opcodes.LSTORE, Opcodes.DSTORE -> -2;
                    case Opcodes.RET -> 0;
                    default -> -1;
                };
                add(opcode, varIndex, effect, Math.max(0, -effect));
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                add(opcode, type, opcode == Opcodes.NEW ? 1 : 0, opcode == Opcodes.NEW ? 0 : 1);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                int size = Type.getType(descriptor).getSize();
                int effect = switch (opcode) {
                    case Opcodes.GETSTATIC -> size;
                    case Opcodes.PUTSTATIC -> -size;
                    case Opcodes.GETFIELD -> size - 1;
                    default -> -size - 1;
                };
                int pops = switch (opcode) {
                    case Opcodes.GETSTATIC -> 0;
                    case Opcodes.PUTSTATIC -> size;
                    case Opcodes.GETFIELD -> 1;
                    default -> size + 1;
                };
                add(opcode, owner + "." + field, effect, pops);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                int sizes = Type.getArgumentsAndReturnSizes(descriptor);
                int argumentSlots = (sizes >> 2) - 1;
                int returnSize = sizes & 0x03;
                int pops = argumentSlots + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);
                int effect = returnSize - pops;
                Type[] arguments = Type.getArgumentTypes(descriptor);
                if (QUERY_OWNERS.contains(owner) && QUERY_METHODS.contains(method) && arguments.length > 0
                        && arguments[0].getDescriptor().equals("Ljava/lang/String;")) {
                    sinks.add(insns.size());
                    add(opcode, new Sink(owner, method, argumentSlots), effect, pops);
                } else {
                    add(opcode, owner + "." + method, effect, pops);
                }
            }

            @Override
            public void visitInvokeDynamicInsn(String method, String descriptor, Handle bootstrap, Object... arguments) {
                int sizes = Type.getArgumentsAndReturnSizes(descriptor);
                int pops = (sizes >> 2) - 1;
                add(Opcodes.INVOKEDYNAMIC, bootstrap.getOwner(), (sizes & 0x03) - pops, pops);
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                int effect = switch (opcode) {
                    case Opcodes.GOTO -> 0;
                    case Opcodes.JSR -> 1;
                    case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
                         Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> -2;
                    default -> -1;
                };
                jumpTo(label, depth + effect);
                add(opcode, label, effect, Math.max(0, -effect));
            }

            @Override
            public void visitLdcInsn(Object value) {
                add(Opcodes.LDC, value, value instanceof Long || value instanceof Double ? 2 : 1, 0);
            }

            @Override
            public void visitIincInsn(int varIndex, int increment) {
                add(Opcodes.IINC, varIndex, 0, 0);
            }

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                switchTo(dflt, labels);
                add(Opcodes.TABLESWITCH, switchTargets(dflt, labels), -1, 1);
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                switchTo(dflt, labels);
                add(Opcodes.LOOKUPSWITCH, switchTargets(dflt, labels), -1, 1);
            }

            @Override
            public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                add(Opcodes.MULTIANEWARRAY, descriptor, 1 - dimensions, dimensions);
            }

            @Override
            public void visitEnd() {
                for (int index : sinks) {
                    Insn insn = insns.get(index);
                    Sink sink = (Sink) insn.operand();
                    int position = insn.depthBefore() - sink.argumentSlots();
                    if (!consistent || position < 0
                            || !producedConstant(position, index - 1, new HashSet<>(), new HashSet<>())) {
                        violations.add(name + " passes non-constant text to " + sink.owner().replace('/', '.') + "."
                                + sink.method());
                    }
                }
            }

            /**
             * Whether every instruction that can have left the value at stack position {@code position} when
             * control reaches {@code from + 1} pushed a string constant.
             */
            private boolean producedConstant(int position, int from, Set<Integer> visitingLocals,
                                             Set<Long> visitedMerges) {
                for (int i = from; i >= 0; i--) {
                    Insn insn = insns.get(i);
                    if (insn.opcode() == LABEL) {
                        Label label = (Label) insn.operand();
                        if (handlerLabels.contains(label)) {
                            return false;
                        }
                        List<Integer> jumps = jumpsTo(label);
                        if (jumps.isEmpty()) {
                            continue;
                        }
                        if (!visitedMerges.add(((long) i << 32) | position)) {
                            return true; // a loop back to a merge already being checked adds no new producer
                        }
                        for (int jump : jumps) {
                            if (!producedConstant(position, jump - 1, visitingLocals, visitedMerges)) {
                                return false;
                            }
                        }
                        return i == 0 || flowEnds(insns.get(i - 1).opcode())
                                || producedConstant(position, i - 1, visitingLocals, visitedMerges);
                    }
                    if (insn.lowestTouched() <= position) {
                        // This instruction produced (or replaced) the value at that position: it is the producer.
                        return isConstantValue(insn, visitingLocals, visitedMerges);
                    }
                }
                return false;
            }

            private boolean isConstantValue(Insn insn, Set<Integer> visitingLocals, Set<Long> visitedMerges) {
                if (insn.opcode() == Opcodes.LDC) {
                    return insn.operand() instanceof String;
                }
                if (insn.opcode() == Opcodes.ALOAD) {
                    return isConstantLocal((Integer) insn.operand(), visitingLocals, visitedMerges);
                }
                return false;
            }

            /** A local that is not a parameter and whose every store writes a constant. */
            private boolean isConstantLocal(int slot, Set<Integer> visitingLocals, Set<Long> visitedMerges) {
                if (slot < parameterSlots || !visitingLocals.add(slot)) {
                    return false;
                }
                boolean stored = false;
                for (int i = 0; i < insns.size(); i++) {
                    Insn insn = insns.get(i);
                    if (insn.opcode() == Opcodes.ASTORE && insn.operand().equals(slot)) {
                        stored = true;
                        if (!producedConstant(insn.depthBefore() - 1, i - 1, visitingLocals, new HashSet<>())) {
                            return false;
                        }
                    }
                }
                visitingLocals.remove(slot);
                return stored;
            }

            private List<Integer> jumpsTo(Label label) {
                List<Integer> sources = new ArrayList<>();
                for (int i = 0; i < insns.size(); i++) {
                    Insn insn = insns.get(i);
                    if (insn.opcode() != LABEL && (insn.operand() == label
                            || (insn.operand() instanceof List<?> targets && targets.contains(label)))) {
                        sources.add(i);
                    }
                }
                return sources;
            }

            private void switchTo(Label dflt, Label[] labels) {
                jumpTo(dflt, depth - 1);
                for (Label label : labels) {
                    jumpTo(label, depth - 1);
                }
            }

            private static boolean flowEnds(int opcode) {
                return opcode == Opcodes.GOTO || opcode == Opcodes.ATHROW || opcode == Opcodes.TABLESWITCH
                        || opcode == Opcodes.LOOKUPSWITCH || (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN);
            }

            private static List<Label> switchTargets(Label dflt, Label[] labels) {
                List<Label> targets = new ArrayList<>(List.of(labels));
                targets.add(dflt);
                return targets;
            }

            /**
             * Slots popped (read or rearranged) by the zero-operand instructions. {@code DUP} and {@code DUP2}
             * count as 0: they leave the original value in place and push a copy, whose producer is the
             * {@code DUP} itself (never a constant, so this errs on the safe side).
             */
            private static int simplePops(int opcode) {
                if (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD) {
                    return 2;
                }
                if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) {
                    return opcode == Opcodes.LASTORE || opcode == Opcodes.DASTORE ? 4 : 3;
                }
                if (opcode >= Opcodes.IADD && opcode <= Opcodes.DREM) {
                    int kind = (opcode - Opcodes.IADD) % 4;
                    return kind == 1 || kind == 3 ? 4 : 2;
                }
                if (opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG) {
                    return opcode == Opcodes.LNEG || opcode == Opcodes.DNEG ? 2 : 1;
                }
                if (opcode >= Opcodes.ISHL && opcode <= Opcodes.LUSHR) {
                    return (opcode - Opcodes.ISHL) % 2 == 1 ? 3 : 2;
                }
                if (opcode >= Opcodes.IAND && opcode <= Opcodes.LXOR) {
                    return (opcode - Opcodes.IAND) % 2 == 1 ? 4 : 2;
                }
                return switch (opcode) {
                    case Opcodes.POP, Opcodes.ARRAYLENGTH, Opcodes.ATHROW, Opcodes.MONITORENTER, Opcodes.MONITOREXIT,
                         Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.ARETURN, Opcodes.I2L, Opcodes.I2F, Opcodes.I2D,
                         Opcodes.F2I, Opcodes.F2L, Opcodes.F2D, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> 1;
                    case Opcodes.POP2, Opcodes.SWAP, Opcodes.DUP_X1, Opcodes.LRETURN, Opcodes.DRETURN, Opcodes.L2I,
                         Opcodes.L2F, Opcodes.L2D, Opcodes.D2I, Opcodes.D2L, Opcodes.D2F, Opcodes.FCMPL,
                         Opcodes.FCMPG -> 2;
                    case Opcodes.DUP_X2, Opcodes.DUP2_X1 -> 3;
                    case Opcodes.DUP2_X2, Opcodes.LCMP, Opcodes.DCMPL, Opcodes.DCMPG -> 4;
                    default -> 0; // NOP, constants, DUP, DUP2, RETURN
                };
            }

            /** Stack effect (in slots) of the zero-operand instructions. */
            private static int simpleEffect(int opcode) {
                if (opcode == Opcodes.NOP) {
                    return 0;
                }
                if (opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.DCONST_1) {
                    return opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1 || opcode == Opcodes.DCONST_0
                            || opcode == Opcodes.DCONST_1 ? 2 : 1;
                }
                if (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD) {
                    return opcode == Opcodes.LALOAD || opcode == Opcodes.DALOAD ? 0 : -1;
                }
                if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) {
                    return opcode == Opcodes.LASTORE || opcode == Opcodes.DASTORE ? -4 : -3;
                }
                if (opcode >= Opcodes.IADD && opcode <= Opcodes.DREM) {
                    int kind = (opcode - Opcodes.IADD) % 4;
                    return kind == 1 || kind == 3 ? -2 : -1;
                }
                if (opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG) {
                    return 0;
                }
                if (opcode >= Opcodes.ISHL && opcode <= Opcodes.LUSHR) {
                    return -1;
                }
                if (opcode >= Opcodes.IAND && opcode <= Opcodes.LXOR) {
                    return (opcode - Opcodes.IAND) % 2 == 1 ? -2 : -1;
                }
                return switch (opcode) {
                    case Opcodes.POP -> -1;
                    case Opcodes.POP2 -> -2;
                    case Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2 -> 1;
                    case Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2 -> 2;
                    case Opcodes.I2L, Opcodes.I2D, Opcodes.F2L, Opcodes.F2D -> 1;
                    case Opcodes.L2I, Opcodes.L2F, Opcodes.D2I, Opcodes.D2F -> -1;
                    case Opcodes.LCMP, Opcodes.DCMPL, Opcodes.DCMPG -> -3;
                    case Opcodes.FCMPL, Opcodes.FCMPG -> -1;
                    case Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.ARETURN, Opcodes.ATHROW, Opcodes.MONITORENTER,
                         Opcodes.MONITOREXIT -> -1;
                    case Opcodes.LRETURN, Opcodes.DRETURN -> -2;
                    default -> 0; // SWAP, I2F, L2D, F2I, D2L, I2B, I2C, I2S, RETURN, ARRAYLENGTH
                };
            }
        }
    }

    /** Compiled controller signatures for {@link #entityExposureRuleDetectsEntitiesInAnyPackageAndInsideGenerics()}. */
    static final class EntityFixtures {

        private EntityFixtures() {
        }

        @RestController
        static class ExposingController {
            public List<RefreshToken> tokens() {
                return List.of();
            }

            public Page<SecurityEvent> events() {
                return Page.empty();
            }

            public ResponseEntity<LoginAttempt> attempt() {
                return ResponseEntity.noContent().build();
            }

            public void update(Account account) {
                account.getId();
            }

            public ResponseEntity<Map<String, List<Course>>> nested() {
                return ResponseEntity.ok(Map.of());
            }
        }

        @RestController
        static class CleanController {
            public ResponseEntity<List<CourseResponse>> courses() {
                return ResponseEntity.ok(List.of());
            }
        }
    }

    /** Compiled examples for {@link #queryRuleRejectsBuiltOrPassedInTextAndAcceptsConstants()}; never executed. */
    static final class QueryFixtures {

        private QueryFixtures() {
        }

        static final class Concatenating {
            private EntityManager entityManager;

            Object byName(String name) {
                return entityManager.createQuery("SELECT a FROM Account a WHERE a.firstName = '" + name + "'")
                        .getResultList();
            }
        }

        static final class Formatting {
            private EntityManager entityManager;

            Object ordered(String column) {
                return entityManager.createNativeQuery(String.format("SELECT * FROM account ORDER BY %s", column))
                        .getResultList();
            }
        }

        static final class HelperBuilt {
            private EntityManager entityManager;

            Object viaHelper(String column) {
                return entityManager.createNativeQuery(orderBy(column)).getResultList();
            }

            private static String orderBy(String column) {
                return "SELECT * FROM account ORDER BY " + column;
            }
        }

        static final class ParameterPassed {
            private EntityManager entityManager;

            Object run(String jpql) {
                return entityManager.createQuery(jpql).getResultList();
            }
        }

        static final class ConditionalConcatenation {
            private EntityManager entityManager;

            Object pick(boolean filtered, String name) {
                String jpql = filtered ? "SELECT a FROM Account a WHERE a.firstName = '" + name + "'"
                        : "SELECT a FROM Account a";
                return entityManager.createQuery(jpql).getResultList();
            }
        }

        static final class LocalReassignedFromParameter {
            private EntityManager entityManager;

            Object reassigned(boolean custom, String text) {
                String jpql = "SELECT a FROM Account a";
                if (custom) {
                    jpql = text;
                }
                return entityManager.createQuery(jpql).getResultList();
            }
        }

        static final class FieldText {
            private EntityManager entityManager;
            private String configuredQuery = "SELECT a FROM Account a";

            Object fromField() {
                return entityManager.createQuery(configuredQuery).getResultList();
            }
        }

        static final class Constant {
            private static final String BASE = "SELECT a FROM Account a ";
            private EntityManager entityManager;

            Object byName(String name, boolean active) {
                String query = active
                        ? BASE + "WHERE a.firstName = :name AND a.deleted = 0"
                        : BASE + "WHERE a.firstName = :name";
                return entityManager.createQuery(query).setParameter("name", name).getResultList();
            }
        }

        static final class ConstantWithResultClass {
            private EntityManager entityManager;

            Object byId(long id) {
                return entityManager.createNativeQuery("SELECT * FROM account WHERE id = :id", Account.class)
                        .setParameter("id", id).getResultList();
            }
        }

        static final class ConstantTransformed {
            private static final String TEMPLATE = "SELECT a FROM Account a ORDER BY %s";
            private EntityManager entityManager;

            Object filled(String column) {
                return entityManager.createQuery(TEMPLATE.formatted(column)).getResultList();
            }
        }

        static final class JdbcConstantWithVarargs {
            private static final String CLAIM = "SELECT id FROM delivery WHERE due <= ? AND attempts < ? LIMIT ?";
            private org.springframework.jdbc.core.JdbcTemplate jdbc;

            Object claim(java.time.Instant now, int attempts) {
                return jdbc.queryForList(CLAIM, Long.class, now.plusSeconds(30).atOffset(java.time.ZoneOffset.UTC),
                        attempts > 0 ? attempts : 1, Math.max(1, attempts * 2));
            }
        }

        static final class JdbcBuiltWithVarargs {
            private org.springframework.jdbc.core.JdbcTemplate jdbc;

            Object claim(String table, java.time.Instant now) {
                return jdbc.queryForList("SELECT id FROM " + table + " WHERE due <= ?", Long.class, now);
            }
        }

        static final class StaticConstant {
            private static final String COUNT = "SELECT count(a) FROM Account a";
            private EntityManager entityManager;

            Object count() {
                return entityManager.createQuery(COUNT, Long.class).getSingleResult();
            }
        }
    }
}
