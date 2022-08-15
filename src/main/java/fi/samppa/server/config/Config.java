package fi.samppa.server.config;



import java.io.*;
import java.util.Properties;

public class Config extends Properties {
    private final File file;

    public Config(Properties defaults, String folder, String path) throws Exception{
        super(defaults);
        this.file = new File(folder, path);
        load();
        save();
    }

    public static Config initConfig(String folder, String path) {
        try {
            Properties properties = loadDefaults(path);
            return new Config(properties, folder, path);
        }
        catch (Exception e){
            e.printStackTrace();
        }
        return null;
    }

    public void load() throws Exception {
        if(!file.exists()){
            file.getParentFile().mkdirs();
            file.createNewFile();
        }
        for(Object key : defaults.keySet()){
            setProperty(key.toString(), defaults.getProperty(key.toString()));
        }
        load(new FileInputStream(file.getAbsolutePath()));
    }

    public static Properties loadDefaults(String path) throws Exception{
        InputStream in = Config.class.getResourceAsStream("/" + path);
        Properties defaults = new Properties();
        defaults.load(in);
        return defaults;
    }

    public void save() throws IOException {
        if(!file.exists()) {
            if(!file.createNewFile()) return;
        }

        FileOutputStream stream = new FileOutputStream(file.getAbsolutePath());
        store(stream, "");
        stream.close();
    }

    public int getInt(String key){
        return Integer.parseInt(getProperty(key));
    }
    public boolean getBoolean(String key){
        return Boolean.parseBoolean(getProperty(key));
    }
    public double getDouble(String key){
        return Double.parseDouble(getProperty(key));
    }
    public float getFloat(String key){
        return Float.parseFloat(getProperty(key));
    }
}

