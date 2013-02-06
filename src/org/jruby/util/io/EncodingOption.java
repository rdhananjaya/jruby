package org.jruby.util.io;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jruby.Ruby;
import org.jruby.RubyEncoding;
import org.jruby.RubyHash;
import org.jruby.RubyNumeric;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.encoding.EncodingService;
import org.jruby.util.TypeConverter;

public class EncodingOption {
    private Encoding externalEncoding;
    private Encoding internalEncoding;
    private boolean bom;

    public EncodingOption(Encoding externalEncoding, Encoding internalEncoding, boolean bom) {
        this.externalEncoding = externalEncoding;
        this.internalEncoding = internalEncoding;
        this.bom = bom;
    }

    public Encoding getExternalEncoding() {
        return externalEncoding;
    }

    public Encoding getInternalEncoding() {
        return internalEncoding;
    }

    public boolean hasBom() {
        return bom;
    }
    
    public static Encoding toEncoding(ThreadContext context, IRubyObject object) {
        if (object instanceof RubyEncoding) return ((RubyEncoding) object).getEncoding();
        
        return context.runtime.getEncodingService().getEncodingFromObject(object);
    }

    // FIXME: This could be smarter amount determining whether optionsArg is a RubyHash and !null (invariant)
    // mri: extract_binmode
    public static int extractBinmode(Ruby runtime, IRubyObject optionsArg, int fmode) {
        IRubyObject v = hashARef(runtime, optionsArg, "text_mode");
        if (!v.isNil()) fmode |= ModeFlags.TEXT;
        
        v = hashARef(runtime, optionsArg, "bin_mode");
        if (!v.isNil()) fmode |= ModeFlags.BINARY;

//        if ((fmode & ModeFlags.BINARY) != 0 && (fmode & ModeFlags.TEXT) != 0) {
//            throw runtime.newArgumentError("both textmode and binmode specified");
//        }
        
        return fmode;
    }
    
    private static IRubyObject hashARef(Ruby runtime, IRubyObject hash, String symbol) {
        if (hash == null || !(hash instanceof RubyHash)) return runtime.getNil();
        
        IRubyObject value = ((RubyHash) hash).fastARef(runtime.newSymbol(symbol));
        
        return value == null ? runtime.getNil() : value;
    }
    
    public static Encoding ascii8bitEncoding(Ruby runtime) {
        return runtime.getEncodingService().getAscii8bitEncoding();   
    }
    
    // mri: rb_io_extract_modeenc
    public static ModeFlags extractModeEncoding(ThreadContext context, 
            IOEncodable ioEncodable, IRubyObject vmode, IRubyObject[] vperm, IRubyObject options, boolean secondTime) {
        int fmode;
        boolean hasEncoding = false;
        int oflags = 0;
        
        // Give default encodings
        setupReadWriteEncodings(context, ioEncodable, null, null);

        if (vmode == null || vmode.isNil()) {
            fmode = OpenFile.READABLE;
            oflags = ModeFlags.RDONLY;
        } else {
            IRubyObject intMode = TypeConverter.checkIntegerType(context.runtime, vmode, "to_int");
            
            if (!intMode.isNil()) {
                vmode = intMode;
                oflags = RubyNumeric.num2int(intMode);
                fmode = ModeFlags.getOpenFileFlagsFor(oflags);
            } else {
                String p = vmode.convertToString().asJavaString();
                int colonSplit = p.indexOf(":");
                try {
                    oflags = ModeFlags.getOFlagsFromString(colonSplit == -1 ? p : p.substring(0, colonSplit));
                    fmode = ModeFlags.getOpenFileFlagsFor(oflags);
                } catch (InvalidValueException e) {
                    fmode = 0;
                    // FIXME: Raise something
                }
                
                if (colonSplit != -1) {
                    hasEncoding = true;
                    parseModeEncoding(context, ioEncodable, p.substring(colonSplit + 1));
                } else {
                    Encoding e = (fmode & ModeFlags.BINARY) != 0 ? ascii8bitEncoding(context.runtime) : null;
                    setupReadWriteEncodings(context, ioEncodable, e, null);
                }
            }
        }
        
        if (options == null || options.isNil()) {
            // FIXME: Set up ecflags
        } else {
            fmode = extractBinmode(context.runtime, options, fmode);
            // FIXME: What is DEFAULT_TEXTMODE
            
            if (!secondTime) {
                IRubyObject v = hashARef(context.runtime, options, "mode");
                
                if (!v.isNil()) {
                    if (vmode != null && !vmode.isNil()) {
                       throw context.runtime.newArgumentError("mode specified twice");
                    }
                    secondTime = true;
                    vmode = v;
                  
                    return extractModeEncoding(context, ioEncodable, vmode, vperm, options, true);
                }
            }
            IRubyObject v = hashARef(context.runtime, options, "perm");
            if (!v.isNil()) {
                if (vperm[0] != null) {
                    if (!vperm[0].isNil()) throw context.runtime.newArgumentError("perm specified twice");
                    
                    vperm[0] = v;
                }
            }
        
            if (getEncodingOptionFromObject(context, ioEncodable, options)) {
                if (hasEncoding) throw context.runtime.newArgumentError("encoding specified twice");
            }
            
            
        }
        
        try {
            return new ModeFlags(oflags);
        } catch (InvalidValueException e) {
            return new ModeFlags();
        }
    }

    // mri: rb_io_extract_encoding_option
    public static boolean getEncodingOptionFromObject(ThreadContext context, IOEncodable ioEncodable, IRubyObject options) {
        if (options == null || options.isNil() || !(options instanceof RubyHash)) return false;

        RubyHash opts = (RubyHash) options;        
        boolean extracted = false;
        Encoding externalEncoding = null;
        
        Ruby runtime = options.getRuntime();
        IRubyObject encodingOpt = opts.fastARef(runtime.newSymbol("encoding"));
        IRubyObject externalOpt = opts.fastARef(runtime.newSymbol("external_encoding"));
        IRubyObject internalOpt = opts.fastARef(runtime.newSymbol("internal_encoding"));
        
        if ((externalOpt != null || internalOpt != null) && encodingOpt != null && !encodingOpt.isNil()) {
                runtime.getWarnings().warn("Ignoring encoding parameter '" + encodingOpt + "': " + 
                        (externalOpt == null ? "internal" : "external") + "_encoding is used");
                encodingOpt = null;
        }
        
        if (externalOpt != null && !externalOpt.isNil()) externalEncoding = toEncoding(context, externalOpt);

        Encoding internalEncoding = null;

        if (internalOpt != null) {
            if (internalOpt.isNil() || internalOpt.asString().toString().equals("-")) {
                internalEncoding = null;
            } else {
                internalEncoding = toEncoding(context, internalOpt);
            }
            
            if (externalEncoding == internalEncoding) internalEncoding = null;
        }
        
        if (encodingOpt != null && !encodingOpt.isNil()) {
            extracted = true;
            
            IRubyObject tmp = encodingOpt.checkStringType19();
            if (!tmp.isNil()) {
                parseModeEncoding(context, ioEncodable, tmp.convertToString().toString());
            } else {
                setupReadWriteEncodings(context, ioEncodable, toEncoding(context, tmp), null);
            }
        } else if (externalOpt != null || internalEncoding != null) {
            extracted = true;
            setupReadWriteEncodings(context, ioEncodable, internalEncoding, externalEncoding);
        }
        
        return extracted;
    }
    
    // mri: rb_io_ext_int_to_encs
    public static void setupReadWriteEncodings(ThreadContext context, IOEncodable encodable, 
            Encoding internal, Encoding external) {
        boolean defaultExternal = false;
        
        if (external == null) {
            external = context.runtime.getDefaultExternalEncoding();
            defaultExternal = true;
        }
        
        if (internal == null && external != ascii8bitEncoding(context.runtime)) {
            internal = context.runtime.getDefaultInternalEncoding();
        }
        
        if (internal == null || internal == external) { // missing internal == nil?
            encodable.setReadEncoding((defaultExternal && internal != external) ? null : external);
            encodable.setWriteEncoding(null);
        } else {
            encodable.setReadEncoding(internal);
            encodable.setWriteEncoding(external);
        }
    }    

    // mri: parse_mode_enc
    public static void parseModeEncoding(ThreadContext context, IOEncodable ioEncodable, String option) {
        Ruby runtime = context.runtime;
        EncodingService service = runtime.getEncodingService();
        Encoding intEncoding = null;

        String[] encs = option.split(":", 2);

        if (encs[0].toLowerCase().startsWith("bom|utf-")) {
            ioEncodable.setBOM(true);
            encs[0] = encs[0].substring(4);
        }

        Encoding extEncoding = service.getEncodingFromString(encs[0]);

        if (encs.length > 1) {
            if (encs[1].equals("-")) {
                // null;
            } else {
                intEncoding = service.getEncodingFromString(encs[1]);
            }
        }

        setupReadWriteEncodings(context, ioEncodable, intEncoding, extEncoding);
    }

    @Override
    public String toString() {
        return "EncodingOption(int:" + internalEncoding + ", ext:" + externalEncoding + ", bom:" + bom + ")";
    }
}
