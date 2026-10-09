package kz.hrms.splitupauth.payment.gateway.freedom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered, lossless representation of a FreedomPay message (request, response or callback).
 *
 * <p>A {@code Map<String,String>} cannot represent what the signature rule covers: FreedomPay signs
 * "all message fields in alphabetical order … fields with the same name are taken in the order they
 * appear in the message … applied recursively to nested tags". This class keeps every field,
 * including repeated names and nested XML elements, in message order so the signature can be
 * computed over exactly what was received.
 */
public final class FreedomPayMessage {

  /** A field is either a text leaf ({@code value != null}) or a nested element. */
  public record Field(String name, String value, List<Field> children) {
    public Field {
      children = children == null ? List.of() : List.copyOf(children);
    }

    public static Field leaf(String name, String value) {
      return new Field(name, value == null ? "" : value, List.of());
    }

    public static Field nested(String name, List<Field> children) {
      return new Field(name, null, children);
    }

    public boolean isNested() {
      return value == null;
    }
  }

  private final List<Field> fields;

  public FreedomPayMessage(List<Field> fields) {
    this.fields = List.copyOf(fields);
  }

  /** Builds a message from flat parameters (requests and form-encoded callbacks). */
  public static FreedomPayMessage of(Map<String, String> params) {
    List<Field> list = new ArrayList<>();
    params.forEach((k, v) -> list.add(Field.leaf(k, v)));
    return new FreedomPayMessage(list);
  }

  /** Builds a message from multi-valued form parameters, keeping repeated values in order. */
  public static FreedomPayMessage ofMulti(Map<String, ? extends List<String>> params) {
    List<Field> list = new ArrayList<>();
    params.forEach(
        (k, values) -> {
          if (values == null || values.isEmpty()) {
            list.add(Field.leaf(k, ""));
          } else {
            values.forEach(v -> list.add(Field.leaf(k, v)));
          }
        });
    return new FreedomPayMessage(list);
  }

  public List<Field> fields() {
    return Collections.unmodifiableList(fields);
  }

  /** First top-level text value for {@code name}, or null. */
  public String get(String name) {
    for (Field f : fields) {
      if (f.name().equals(name) && !f.isNested()) {
        return f.value();
      }
    }
    return null;
  }

  /** Number of top-level fields named {@code name}. */
  public int count(String name) {
    int n = 0;
    for (Field f : fields) {
      if (f.name().equals(name)) n++;
    }
    return n;
  }

  /**
   * Convenience view for business code: first occurrence of every top-level text field. Never use
   * this view for signature computation.
   */
  public Map<String, String> firstValues() {
    Map<String, String> map = new LinkedHashMap<>();
    for (Field f : fields) {
      if (!f.isNested()) {
        map.putIfAbsent(f.name(), f.value());
      }
    }
    return map;
  }
}
