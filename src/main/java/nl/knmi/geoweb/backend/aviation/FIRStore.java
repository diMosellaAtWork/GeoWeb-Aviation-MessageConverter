package nl.knmi.geoweb.backend.aviation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.geojson.GeoJsonObject;
import org.geojson.GeoJsonObjectVisitor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;
import lombok.Setter;
import nl.knmi.adaguc.tools.Debug;
import nl.knmi.adaguc.tools.Tools;
import nl.knmi.geoweb.backend.product.sigmet.geo.GeoUtils;

@Getter
@Setter
@Component
public class FIRStore implements Cloneable {
	private String worldFIRFile;
	private String delegatedFile;
	private String simplifiedFIRFile;
	private String directory;
	private Map<String, Feature> worldFIRInfos;
	private Map<String, Feature> simplifiedFIRInfos;
	private Map<String, List<Feature>> delegatedAirspaces;

	public FIRStore(@Value(value = "${productstorelocation}") String productstorelocation) {
		this.directory = productstorelocation + "/admin/config";
		try {
			Tools.mksubdirs(productstorelocation + "/admin/config");
		} catch (IOException e) {
			Debug.println("Creation of " + productstorelocation + "/admin/config" + " failed");
		}
		this.worldFIRFile = "world_firs.json";
		this.delegatedFile = "delegated.json";
		this.simplifiedFIRFile = "simplified_firs.json";
	}

	public void initStore() throws IOException {
		this.worldFIRInfos = new HashMap<String, Feature>();
		this.delegatedAirspaces = new HashMap<String, List<Feature>>();
		this.simplifiedFIRInfos = new HashMap<String, Feature>();
		File fn = new File(this.directory + "/" + this.worldFIRFile);
		Debug.println("fn:" + fn);
		if (!(fn.exists() && fn.isFile())) {
			Debug.errprintln("No FIR file found, copying one from resources dir to " + this.directory);
			String s = Tools.readResource(this.worldFIRFile);
			String FIRText = String.format("%s/%s", this.directory, this.worldFIRFile);
			Tools.writeFile(FIRText, s);
		}
		File simplifiedFn = new File(this.directory + "/" + this.simplifiedFIRFile);
		Debug.println("simplifiedFn:" + simplifiedFn);
		if (!(simplifiedFn.exists() && simplifiedFn.isFile())) {
			Debug.errprintln("No simplified FIR file found, copying one from resources dir to " + this.directory);
			String s = Tools.readResource(this.simplifiedFIRFile);
			String FIRText = String.format("%s/%s", this.directory, this.simplifiedFIRFile);
			Tools.writeFile(FIRText, s);
		}
		File delegatedFn = new File(this.directory + "/" + this.delegatedFile);
		Debug.println("delegatedFn:" + delegatedFn);
		if (!(delegatedFn.exists() && delegatedFn.isFile())) {
			Debug.errprintln("No delegated areas FIR file found, copying one from resources dir to " + this.directory);
			// TODO: since the lists of coordinates for delegated area (EHAA) doesn't align
			// with the FIR boundary,
			// we moved a coordinate to make them intersect:
			// [3.16004722222222,52.9310027777778] -> [3.163, 52.92]
			String s = Tools.readResource(this.delegatedFile);
			String FIRText = String.format("%s/%s", this.directory, this.delegatedFile);
			Tools.writeFile(FIRText, s);
		}

		ObjectMapper om = new ObjectMapper();

		try {
			GeoJsonObject FIRInfo = om.readValue(fn, GeoJsonObject.class);
			FeatureCollection fc = (FeatureCollection) FIRInfo;
			for (Feature f : fc.getFeatures()) {
				String FIRname = f.getProperty("FIRname");
				String ICAOCode = f.getProperty("ICAOCODE");
				worldFIRInfos.put(FIRname, f);
				worldFIRInfos.put(ICAOCode, f);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		Debug.println("Found " + worldFIRInfos.size() + " records of FIRinfo");

		try {
			GeoJsonObject simplifiedFIRInfo = om.readValue(simplifiedFn, GeoJsonObject.class);
			FeatureCollection simplifiedFc = (FeatureCollection) simplifiedFIRInfo;
			for (Feature f : simplifiedFc.getFeatures()) {
				String FIRname = f.getProperty("FIRname");
				String ICAOCode = f.getProperty("ICAOCODE");
				simplifiedFIRInfos.put(FIRname, f);
				simplifiedFIRInfos.put(ICAOCode, f);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		Debug.println("Found " + simplifiedFIRInfos.size() + " records of simplified FIRinfo");

		try {
			GeoJsonObject DelegatedInfo = om.readValue(delegatedFn, GeoJsonObject.class);
			FeatureCollection fc = (FeatureCollection) DelegatedInfo;
			for (Feature f : fc.getFeatures()) {
				String FIRname = f.getProperty("FIRname");
				String ICAOCode = f.getProperty("ICAONAME");
				if (!delegatedAirspaces.containsKey(FIRname)) {
					List<Feature> delegated = new ArrayList<Feature>();
					delegatedAirspaces.put(FIRname, delegated);
					delegatedAirspaces.put(ICAOCode, delegated);
				}
				delegatedAirspaces.get(FIRname).add(f);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unchecked")
	public static Feature cloneThroughSerialize(Feature t) {
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			serializeToOutputStream(t, bos);
			byte[] bytes = bos.toByteArray();
			ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
			return (Feature) ois.readObject();
		} catch (Exception e) {
			return null;
		}
	}

	private static void serializeToOutputStream(Serializable ser, OutputStream os) throws IOException {
		ObjectOutputStream oos = null;
		try {
			oos = new ObjectOutputStream(os);
			oos.writeObject(ser);
			oos.flush();
		} finally {
			oos.close();
		}
	}

	public Feature lookup(String name, boolean addDelegated) {
		if (worldFIRInfos == null) {
			try {
				initStore();
			} catch (IOException e) {
				return null;
			}
		}
		if (addDelegated) {
			// Debug.println("Should add delegated airspaces here for "+name);

		}
		Feature feature = null;
		if (simplifiedFIRInfos.containsKey(name)) {
			feature = cloneThroughSerialize(simplifiedFIRInfos.get(name));
		} else if (worldFIRInfos.containsKey(name)) {
			feature = cloneThroughSerialize(worldFIRInfos.get(name));
		}
		// Debug.println("Feature lookup("+name+") "+feature+"
		// ["+delegatedAirspaces.containsKey(name)+"]");

		if (delegatedAirspaces.containsKey(name)) {
			for (Feature f : delegatedAirspaces.get(name)) {
				// Merge f with feature
				// Debug.println("Adding delegated area for "+name);
				feature = GeoUtils.merge(feature, f);
			}
		}

		return feature;
	}

}
