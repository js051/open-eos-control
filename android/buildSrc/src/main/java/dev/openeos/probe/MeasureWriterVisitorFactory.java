package dev.openeos.probe;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.ClassVisitor;

/** LOCAL EXPERIMENT ONLY. Registered exclusively for explicitly opted-in debug variants. */
public abstract class MeasureWriterVisitorFactory
        implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    @Override public boolean isInstrumentable(ClassData data) {
        return data.getClassName().equals(MeasureWriterVisitor.TARGET.replace('/', '.'));
    }
    @Override public ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        return new MeasureWriterVisitor(next);
    }
}
