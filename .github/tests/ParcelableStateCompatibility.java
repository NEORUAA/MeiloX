import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Opt-in Android shell fixture. Only synthetic state and the supplied module DEX are loaded. */
public final class ParcelableStateCompatibility {
    private static final String PREFIX = "androidx.compose.runtime.";
    private static final String[] TYPES = {
        PREFIX + "ParcelableSnapshotMutableFloatState",
        PREFIX + "ParcelableSnapshotMutableIntState",
        PREFIX + "ParcelableSnapshotMutableLongState",
        PREFIX + "ParcelableSnapshotMutableState",
        PREFIX + "snapshots.SnapshotStateList"
    };
    private static final String LIBRARY_ENUM = "com.ljyh.mei.ui.navigation.LibraryPage";
    private static final String[] LIBRARY_VALUES = {
        "Songs", "Playlists", "Podcasts", "Downloads", "Cloud", "History"
    };

    private static final class State {
        final String name;
        final byte[] payload;
        final String enumName;

        State(String name, byte[] payload) {
            this(name, payload, null);
        }

        State(String name, byte[] payload, String enumName) {
            this.name = name;
            this.payload = payload;
            this.enumName = enumName;
        }
    }

    private static final class ModuleLoader extends DexClassLoader {
        ModuleLoader(String apk) {
            super(apk, null, null, Parcel.class.getClassLoader());
        }

        boolean applicationLoaded() {
            return findLoadedClass("com.ljyh.mei.AppContext") != null;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static ModuleLoader loader(String apk) {
        require(new File(apk).isFile(), "Module APK does not exist");
        return new ModuleLoader(apk);
    }

    private static State state(String name, int kind, int policy) {
        Parcel parcel = Parcel.obtain();
        try {
            switch (kind) {
                case 0: parcel.writeFloat(-1.25f); break;
                case 1: parcel.writeInt(55); break;
                case 2: parcel.writeLong(0x700000001234L); break;
                case 3:
                    parcel.writeValue("parcel-fixture");
                    parcel.writeInt(policy);
                    break;
                case 4:
                    parcel.writeInt(0);
                    break;
                case 5:
                    Bundle item = new Bundle();
                    item.putString("path", "screen/account");
                    item.putInt("tab", 1);
                    parcel.writeInt(3);
                    parcel.writeValue("screen/library");
                    parcel.writeValue(0x700000001234L);
                    parcel.writeValue(item);
                    break;
                default: throw new AssertionError("Unknown state kind");
            }
            return new State(name, parcel.marshall());
        } finally {
            parcel.recycle();
        }
    }

    private static State enumState(ClassLoader loader, String name, int policy) throws Exception {
        Class<?> type = loader.loadClass(LIBRARY_ENUM);
        require(type.isEnum() && type.getClassLoader() == loader, "LibraryPage enum wire identity changed");
        Object[] constants = type.getEnumConstants();
        require(constants != null && constants.length == LIBRARY_VALUES.length, "LibraryPage values changed");
        Serializable value = null;
        for (int index = 0; index < constants.length; index++) {
            Enum<?> constant = (Enum<?>) constants[index];
            require(constant.name().equals(LIBRARY_VALUES[index]), "LibraryPage enum name/order changed");
            if (constant.name().equals(name)) value = constant;
        }
        require(value != null, "LibraryPage fixture value is absent");
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeValue(value);
            parcel.writeInt(policy);
            return new State(TYPES[3], parcel.marshall(), name);
        } finally {
            parcel.recycle();
        }
    }

    private static Parcelable create(ClassLoader loader, State state) throws Exception {
        Class<?> type = loader.loadClass(state.name);
        require(type.getClassLoader() == loader, "State escaped its isolated module loader");
        Field field = type.getField("CREATOR");
        field.setAccessible(true);
        Parcelable.Creator<?> creator = (Parcelable.Creator<?>) field.get(null);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(state.payload, 0, state.payload.length);
            parcel.setDataPosition(0);
            Parcelable value = (Parcelable) creator.createFromParcel(parcel);
            require(parcel.dataAvail() == 0, "State CREATOR did not consume its complete payload");
            return value;
        } finally {
            parcel.recycle();
        }
    }

    private static byte[] payload(Parcelable value) {
        Parcel parcel = Parcel.obtain();
        try {
            value.writeToParcel(parcel, 0);
            return parcel.marshall();
        } finally {
            parcel.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private static Bundle transfer(Bundle source, ClassLoader receiver) {
        Parcel outgoing = Parcel.obtain();
        Parcel incoming = Parcel.obtain();
        try {
            outgoing.writeBundle(source);
            byte[] bytes = outgoing.marshall();
            incoming.unmarshall(bytes, 0, bytes.length);
            incoming.setDataPosition(0);
            Bundle result = incoming.readBundle(receiver);
            require(result != null, "Bundle was lost");
            return result;
        } finally {
            outgoing.recycle();
            incoming.recycle();
        }
    }

    private static void verify(Parcelable value, ClassLoader receiver, State expected) {
        require(value != null && value.getClass().getName().equals(expected.name), "State wire identity changed");
        require(value.getClass().getClassLoader() == receiver, "Decoded state used the writer's loader");
        require(Arrays.equals(payload(value), expected.payload), "Decoded state value or mutation policy changed");
        if (expected.enumName != null) {
            boolean found = false;
            // R8 may rename getters; verify the actual erased state value, not its reserialized descriptor alone.
            for (Method method : value.getClass().getMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0 ||
                        method.getReturnType() != Object.class) continue;
                try {
                    method.setAccessible(true);
                    Object item = method.invoke(value);
                    if (!(item instanceof Enum)) continue;
                    Enum<?> enumValue = (Enum<?>) item;
                    require(enumValue.getDeclaringClass().getName().equals(LIBRARY_ENUM) &&
                            enumValue.getDeclaringClass().getClassLoader() == receiver &&
                            enumValue.name().equals(expected.enumName), "Decoded enum escaped the receiving loader or changed value");
                    found = true;
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError("Cannot inspect the actual decoded enum state", error);
                }
            }
            require(found, "No decoded LibraryPage state getter was inspected");
        }
    }

    @SuppressWarnings("deprecation")
    private static void stableDirection(String label, ClassLoader writer, ClassLoader receiver, boolean libraryEnums) throws Exception {
        List<State> states = new ArrayList<>();
        for (int kind = 0; kind < 3; kind++) states.add(state(TYPES[kind], kind, 0));
        for (int policy = 0; policy < 3; policy++) states.add(state(TYPES[3], 3, policy));
        states.add(state(TYPES[4], 4, 0));
        states.add(state(TYPES[4], 5, 0));
        if (libraryEnums) {
            for (String name : LIBRARY_VALUES) {
                for (int policy = 0; policy < 3; policy++) {
                    State expected = enumState(writer, name, policy);
                    require(Arrays.equals(expected.payload, enumState(receiver, name, policy).payload),
                            "LibraryPage enum serialization differs between APKs");
                    states.add(expected);
                }
            }
        }
        ArrayList<Parcelable> nested = new ArrayList<>();
        for (State state : states) {
            Parcelable value = create(writer, state);
            require(Arrays.equals(payload(value), state.payload), "Writer state differs from the synthetic fixture");
            Bundle source = new Bundle();
            source.putParcelable("state", value);
            source.putString("tail", "tail-sentinel");
            Bundle result = transfer(source, receiver);
            verify(result.getParcelable("state"), receiver, state);
            require("tail-sentinel".equals(result.getString("tail")), "Parcelable corrupted its sibling field");
            nested.add(value);
            System.out.println("PASS " + label + ": " + state.name +
                    (state.enumName == null ? "" : "/" + state.enumName) + " bytes=" + state.payload.length);
        }
        Bundle inner = new Bundle();
        inner.putParcelableArrayList("states", nested);
        Bundle outer = new Bundle();
        outer.putBundle("saved-registry", inner);
        outer.putInt("tail", 55);
        Bundle result = transfer(outer, receiver);
        ArrayList<Parcelable> decoded = result.getBundle("saved-registry").getParcelableArrayList("states");
        require(decoded != null && decoded.size() == states.size(), "Nested state list changed");
        for (int index = 0; index < states.size(); index++) verify(decoded.get(index), receiver, states.get(index));
        require(result.getInt("tail") == 55, "Nested state corrupted its sibling field");
        System.out.println("PASS " + label + ": nested Bundle and Parcelable list with sibling sentinel");
    }

    @SuppressWarnings("deprecation")
    private static void legacyCollision(ClassLoader writer, ClassLoader receiver) throws Exception {
        State legacy = state("z99", 3, 1);
        Parcelable value = create(writer, legacy);
        require(Arrays.equals(payload(value), legacy.payload), "Legacy writer is not the expected generic state");
        State receiverShape = state("z99", 2, 0);
        verify(create(receiver, receiverShape), receiver, receiverShape);
        Bundle bundle = new Bundle();
        bundle.putParcelable("state", value);
        bundle.putInt("tail", 55);
        boolean incompatible;
        try {
            Bundle decoded = transfer(bundle, receiver);
            Parcelable replaced = decoded.getParcelable("state");
            incompatible = replaced == null || !Arrays.equals(payload(replaced), legacy.payload) || decoded.getInt("tail") != 55;
            System.out.println("Legacy collision outcome: changed_payload=" + incompatible);
        } catch (RuntimeException error) {
            incompatible = true;
            System.out.println("Legacy collision outcome: " + error.getClass().getSimpleName());
        }
        require(incompatible, "Legacy alias-collision negative control unexpectedly preserved its value");
        System.out.println("PASS negative control: legacy z99 changes generic-state wire identity to Long state");
    }

    public static void main(String[] args) throws Exception {
        boolean libraryEnums = args.length > 0 && args[0].equals("--library-enums");
        if (libraryEnums) args = Arrays.copyOfRange(args, 1, args.length);
        require(args.length == 2 || args.length == 4, "Supply two stable-name R8 APKs and optionally two known legacy-collision APKs");
        ModuleLoader older = loader(args[0]);
        ModuleLoader current = loader(args[1]);
        stableDirection("older-to-current", older, current, libraryEnums);
        stableDirection("current-to-older", current, older, libraryEnums);
        require(!older.applicationLoaded() && !current.applicationLoaded(), "State fixture loaded the production Application");
        if (args.length == 4) {
            ModuleLoader legacyWriter = loader(args[2]);
            ModuleLoader legacyReader = loader(args[3]);
            legacyCollision(legacyWriter, legacyReader);
            require(!legacyWriter.applicationLoaded() && !legacyReader.applicationLoaded(), "Legacy fixture loaded the production Application");
        }
        System.out.println("PASS Android Parcel fixture; no Application, business request, host hook or user state loaded");
    }
}
