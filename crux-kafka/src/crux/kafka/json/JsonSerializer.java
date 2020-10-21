package crux.kafka.json;

import java.util.Map;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import clojure.java.api.Clojure;
import clojure.lang.AFn;
import clojure.lang.IFn;
import org.apache.kafka.common.serialization.Serializer;
import com.fasterxml.jackson.core.JsonGenerator;

// NOTE: This isn't currently useful for the document topic as it
// would information about what is an id. To do this properly one
// would need a more sophisticated mapping, or something like Transit.

// Or one could detect ids in the values and encode them in a way that
// the deserializer understands.

public class JsonSerializer implements Serializer<Object> {
    private static final IFn generateString;

    static {
        Clojure.var("clojure.core/require").invoke(Clojure.read("cheshire.generate"));
        IFn addEncoder = Clojure.var("cheshire.generate/add-encoder");
        Clojure.var("clojure.core/require").invoke(Clojure.read("crux.codec"));
        IFn ednIdtoOriginalId = Clojure.var("crux.codec/edn-id->original-id");
	Clojure.var("clojure.core/require").invoke(Clojure.read("crux.io"));
        Class<?> ednIdClass =  (Class<?>) Clojure.var("clojure.core/resolve").invoke(Clojure.read("crux.codec.EDNId"));
        addEncoder.invoke(ednIdClass, new AFn() {
                public Object invoke(Object c, Object jsonGenerator) {
                    try {
                        ((JsonGenerator) jsonGenerator).writeString(ednIdtoOriginalId.invoke(c).toString());
                        return null;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
	Class<?> codecIdClass =  (Class<?>) Clojure.var("clojure.core/resolve").invoke(Clojure.read("crux.codec.Id"));
	addEncoder.invoke(codecIdClass, new AFn() {
                public Object invoke(Object c, Object jsonGenerator) {
                    try {
                        ((JsonGenerator) jsonGenerator).writeString(c.toString());
                        return null;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        Clojure.var("clojure.core/require").invoke(Clojure.read("cheshire.core"));
        generateString = Clojure.var("cheshire.core/generate-string");
    }

    public void close() {
    }

    public void configure(Map<String,?> configs, boolean isKey) {
    }

    public byte[] serialize(String topic, Object data) {
        if (data == null) {
            return null;
        }
        try {
            return ((String) generateString.invoke(data)).getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
