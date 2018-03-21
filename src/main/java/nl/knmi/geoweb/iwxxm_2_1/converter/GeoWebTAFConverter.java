package nl.knmi.geoweb.iwxxm_2_1.converter;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import fi.fmi.avi.converter.ConversionHints;
import fi.fmi.avi.converter.ConversionIssue;
import fi.fmi.avi.converter.ConversionResult;
import fi.fmi.avi.model.Aerodrome;
import fi.fmi.avi.model.AviationCodeListUser.CloudAmount;
import fi.fmi.avi.model.AviationCodeListUser.CloudType;
import fi.fmi.avi.model.AviationCodeListUser.RelationalOperator;
import fi.fmi.avi.model.AviationCodeListUser.TAFStatus;
import fi.fmi.avi.model.AviationCodeListUser.TAFChangeIndicator;
import fi.fmi.avi.model.AviationCodeListUser.PermissibleUsage;
import fi.fmi.avi.model.AviationCodeListUser.PermissibleUsageReason;

import fi.fmi.avi.model.CloudForecast;
import fi.fmi.avi.model.Weather;
import fi.fmi.avi.model.impl.CloudForecastImpl;
import fi.fmi.avi.model.impl.CloudLayerImpl;
import fi.fmi.avi.model.impl.NumericMeasureImpl;
import fi.fmi.avi.model.impl.WeatherImpl;
import fi.fmi.avi.model.taf.TAF;
import fi.fmi.avi.model.taf.TAFBaseForecast;
import fi.fmi.avi.model.taf.TAFChangeForecast;
import fi.fmi.avi.model.taf.TAFForecast;
import fi.fmi.avi.model.taf.TAFSurfaceWind;
import fi.fmi.avi.model.taf.impl.TAFBaseForecastImpl;
import fi.fmi.avi.model.taf.impl.TAFChangeForecastImpl;
import fi.fmi.avi.model.taf.impl.TAFImpl;
import fi.fmi.avi.model.taf.impl.TAFSurfaceWindImpl;
import nl.knmi.adaguc.tools.Debug;
import nl.knmi.geoweb.backend.product.taf.Taf;
import nl.knmi.geoweb.backend.product.taf.Taf.Forecast;
import nl.knmi.geoweb.backend.product.taf.Taf.Forecast.TAFCloudType;
import nl.knmi.geoweb.backend.product.taf.Taf.Forecast.TAFVisibility;
import nl.knmi.geoweb.backend.product.taf.Taf.Forecast.TAFWeather;
import nl.knmi.geoweb.backend.product.taf.Taf.Forecast.TAFWind;

public class GeoWebTAFConverter extends AbstractGeoWebConverter<TAF>{

	@Override
	public ConversionResult<TAF> convertMessage(Taf input, ConversionHints hints) {
		ConversionResult<TAF> retval = new ConversionResult<>();
		TAF taf = new TAFImpl();
		retval.setConvertedMessage(taf);

		taf.setTranslatedTAC(input.toTAC());
		taf.setTranslationTime(ZonedDateTime.now());

		TAFStatus st;
		switch (input.getMetadata().getType()){
		case amendment: 
			st=TAFStatus.AMENDMENT;
			break;
		case correction:
			st=TAFStatus.CORRECTION;
			break;
		case canceled:
			st=TAFStatus.CANCELLATION;
		case retarded:
			st=TAFStatus.MISSING;
			break;
		case missing:
			st=TAFStatus.MISSING;
			break;
		default:
			st=TAFStatus.NORMAL;
			break;
		}
		taf.setStatus(st);

		taf.setPermissibleUsage(PermissibleUsage.NON_OPERATIONAL);
		taf.setPermissibleUsageReason(PermissibleUsageReason.TEST);

		Aerodrome ad=new Aerodrome(input.getMetadata().getLocation());
		taf.setAerodrome(ad);

		taf.setValidityStartTime(ZonedDateTime.from(input.getMetadata().getValidityStart()));
		taf.setValidityEndTime(ZonedDateTime.from(input.getMetadata().getValidityEnd()));
		taf.setIssueTime(ZonedDateTime.from(input.getMetadata().getIssueTime()));

		retval.addIssue(updateBaseForecast(taf, input, hints));

		Taf.Forecast previousForecast=input.getForecast();
		previousForecast=updateForecast(previousForecast, previousForecast);

		retval.addIssue(updateChangeForecasts(taf, input, previousForecast, hints));
		return retval;
	}

	private List<ConversionIssue> updateBaseForecast(final TAF fct, final Taf input, ConversionHints hints) {
		List<ConversionIssue> retval = new ArrayList<>();
		TAFBaseForecast baseFct = new TAFBaseForecastImpl();
		retval.addAll(updateForecastSurfaceWind(baseFct, input, hints));

		retval.addAll(updateVisibility(baseFct, input, hints));
		retval.addAll(updateClouds(baseFct, input, hints));
		if (input.getForecast().getCaVOK()!=null) {
			baseFct.setCeilingAndVisibilityOk(input.getForecast().getCaVOK());

			if (!input.getForecast().getCaVOK()) {

				retval.addAll(updateWeather(baseFct, input, hints));


				retval.addAll(updateTemperatures(baseFct, input, hints));
			}
		}

		fct.setBaseForecast(baseFct);
		return retval;

	}

	private List<ConversionIssue> updateForecastSurfaceWind(final TAFForecast fct, final Taf input, ConversionHints hints) {
		List<ConversionIssue> retval = new ArrayList<>();
		TAFSurfaceWind wind=new TAFSurfaceWindImpl();

		Object dir=input.getForecast().getWind().getDirection().toString();
		if ((dir instanceof String)&&(dir.equals("VRB"))) {
			wind.setVariableDirection(true);
		} else if (dir instanceof String) {
			wind.setMeanWindDirection(new NumericMeasureImpl(Integer.parseInt((String)dir),"deg"));
		} else {
			retval.add(new ConversionIssue(ConversionIssue.Type.MISSING_DATA, "Surface wind direction is missing: "));
		} 

		String windSpeedUnit=input.getForecast().getWind().getUnit();
		Debug.println("unit: "+windSpeedUnit+" "+"MPS".equalsIgnoreCase(windSpeedUnit));
		if ("KT".equalsIgnoreCase(windSpeedUnit)) {
			windSpeedUnit="[kn_i]";
		} else if ("MPS".equalsIgnoreCase(windSpeedUnit)) {
			windSpeedUnit="m/s";
		}
		Debug.println("unit2: "+windSpeedUnit+" "+"MPS".equalsIgnoreCase(windSpeedUnit));
		Integer meanSpeed=input.getForecast().getWind().getSpeed();
		if (meanSpeed!=null) {
			wind.setMeanWindSpeed(new NumericMeasureImpl(meanSpeed, windSpeedUnit));
		} else {
			retval.add(new ConversionIssue(ConversionIssue.Type.MISSING_DATA, "Surface wind mean speed is missing: "));
		}

		Integer gustSpeed=input.getForecast().getWind().getGusts();
		if (gustSpeed!=null) {
			wind.setWindGust(new NumericMeasureImpl(gustSpeed, windSpeedUnit));
		}

		fct.setSurfaceWind(wind);

		return retval;
	}

	private List<ConversionIssue> updateChangeForecastSurfaceWind(final TAFForecast fct, final Taf.ChangeForecast input, Forecast previousForecast, ConversionHints hints) {
		List<ConversionIssue> retval = new ArrayList<>();
		TAFSurfaceWind wind=new TAFSurfaceWindImpl();

		TAFWind src=null;
		if (input.getForecast().getWind()!=null) {
			src=input.getForecast().getWind();
		} else {
			Debug.println("updateChangeForecastSurfaceWind() using previous wind");
			src=previousForecast.getWind();
		}
		if (src!=null) {
			Object dir=null;
			dir=src.getDirection().toString();
			if ((dir instanceof String)&&(dir.equals("VRB"))) {
				wind.setVariableDirection(true);
			} else if (dir instanceof String) {
				wind.setMeanWindDirection(new NumericMeasureImpl(Integer.parseInt((String)dir),"deg"));
			} else {
				retval.add(new ConversionIssue(ConversionIssue.Type.MISSING_DATA, "Surface wind direction is missing: "));
			} 

			String windSpeedUnit=src.getUnit();
			if ("KT".equalsIgnoreCase(windSpeedUnit)) {
				windSpeedUnit="[kn_i]";
			} else {
				if ("MPS".equalsIgnoreCase(windSpeedUnit)) {
					windSpeedUnit="m/s";
				}
			}

			Integer meanSpeed=src.getSpeed();
			if (meanSpeed!=null) {
				wind.setMeanWindSpeed(new NumericMeasureImpl(meanSpeed, windSpeedUnit));
			} else {
				retval.add(new ConversionIssue(ConversionIssue.Type.MISSING_DATA, "Surface wind mean speed is missing: "));
			}

			Integer gustSpeed=src.getGusts();
			if (gustSpeed!=null) {
				wind.setWindGust(new NumericMeasureImpl(gustSpeed, windSpeedUnit));
			}
		} else {
			Debug.println("updateChangeForecastSurfaceWind() found null wind");
		}
		fct.setSurfaceWind(wind);

		return retval;
	}

	private List<ConversionIssue> updateVisibility(final TAFForecast fct, final Taf input, ConversionHints hints) {
		List<ConversionIssue> retval = new ArrayList<>();
		if ((input.getForecast()!=null) && (input.getForecast().getVisibility()!=null)) {
			Integer dist=input.getForecast().getVisibility().getValue();
			String unit=input.getForecast().getVisibility().getUnit();
			if (unit==null) unit="m";
			if ((dist!=null)&&(unit!=null)) {
				fct.setPrevailingVisibility(new NumericMeasureImpl(dist, unit));
			} else {
				retval.add(new ConversionIssue(ConversionIssue.Type.MISSING_DATA, "Missing visibility value or unit: "));
			}
			fct.setPrevailingVisibilityOperator(RelationalOperator.ABOVE);
		}
		return retval;
	}

	private List<ConversionIssue> updateChangeVisibility(final TAFForecast fct, final Taf.ChangeForecast input,/* Forecast previousForecast,*/ ConversionHints hints) {
		List<ConversionIssue> retval = new ArrayList<>();
		TAFVisibility src=null;
		if (input.getForecast().getVisibility()!=null){
			src=input.getForecast().getVisibility();
			//		}else {
			//			src=previousForecast.getVisibility();
		}
		Integer dist=null;
		String unit=null;
		if (src!=null) {
			dist=src.getValue();
			unit=src.getUnit();
		} else {
			Debug.println("updateChangeVisibility() found null visibility");
		}
		if (unit==null) unit="m";
		if ((dist!=null)&&(unit!=null)) {
			fct.setPrevailingVisibility(new NumericMeasureImpl(dist, unit));
		} else {
			retval.add(new ConversionIssue(ConversionIssue.Type.MISSING_DATA, "Missing visibility value or unit: "));
		}
		fct.setPrevailingVisibilityOperator(RelationalOperator.ABOVE);
		return retval;
	}

	private List<ConversionIssue> updateWeather(final TAFForecast fct, final Taf input, ConversionHints hints) {
		List<ConversionIssue> retval = new ArrayList<>();
		List<Weather> weatherList=new ArrayList<>();
		for (TAFWeather w:input.getForecast().getWeather()) {
			String code=w.toString();
			fi.fmi.avi.model.Weather weather = new WeatherImpl();
			weather.setCode(code);
			weather.setDescription("Longtext for "+code);
			weatherList.add(weather);
		}
		if (!weatherList.isEmpty()) {
			fct.setForecastWeather(weatherList);
		}
		return retval;
	}

	private List<ConversionIssue> updateChangeWeather(final TAFForecast fct, final Taf.ChangeForecast input, ConversionHints hints) {
		List<ConversionIssue> retval = new ArrayList<>();
		List<Weather> weatherList=new ArrayList<>();
		if (input.getForecast().getWeather()!=null) {
			for (TAFWeather w:input.getForecast().getWeather()) {
				String code=w.toString();
				fi.fmi.avi.model.Weather weather = new WeatherImpl();
				weather.setCode(code);
				weather.setDescription("Longtext for "+code);
				weatherList.add(weather);
			}
		} else {
			Debug.println("updateChangeWeather() found null weather");
		}
		if (!weatherList.isEmpty()) {
			fct.setForecastWeather(weatherList);
		}
		return retval;
	}

	private List<ConversionIssue> updateClouds(final TAFForecast fct, final Taf input, ConversionHints hints) {
		List<ConversionIssue> retval = new ArrayList<>();
		CloudForecast cloud=new CloudForecastImpl();
		List<fi.fmi.avi.model.CloudLayer> layers = new ArrayList<>();
		if (input.getForecast().getClouds()!=null) {
			for (Taf.Forecast. TAFCloudType cldType: input.getForecast().getClouds()) {
				String cover=cldType.getAmount();
				String mod=cldType.getMod();
				Integer height=cldType.getHeight();
				String unit="[ft_i]";
				if ("VV".equals(cover)) {
					if (height==null) {
						retval.add(new ConversionIssue(ConversionIssue.Type.SYNTAX_ERROR, "Cloud layer height not specified"));
					}
					cloud.setVerticalVisibility(new NumericMeasureImpl(height, unit));
				} else if ((cldType.getIsNSC()!=null) &&cldType.getIsNSC()) {
					cloud.setNoSignificantCloud(cldType.getIsNSC());
				} else {
					fi.fmi.avi.model.CloudLayer layer=new CloudLayerImpl();
					if ("FEW".equals(cover)) {
						layer.setAmount(CloudAmount.FEW);
					} else if ("SCT".equals(cover)){
						layer.setAmount(CloudAmount.SCT);
					} else if ("BKN".equals(cover)){
						layer.setAmount(CloudAmount.BKN);  
					} else if ("OVC".equals(cover)){
						layer.setAmount(CloudAmount.OVC);  
					} else if ("SKC".equals(cover)){
						layer.setAmount(CloudAmount.SKC);  
					}
					if ("TCU".equals(mod)) {
						layer.setCloudType(CloudType.TCU);
					} else if ("CB".equals(mod)) {
						layer.setCloudType(CloudType.CB);  
					}
					layer.setBase(new NumericMeasureImpl(height*100, unit));
					layers.add(layer);
				}
			}
		}
		if (!layers.isEmpty()){
			cloud.setLayers(layers);
		}else{
			cloud.setLayers(layers); //TODO add empty layers???
		}
		fct.setCloud(cloud);
		return retval;
	}

	private List<ConversionIssue> updateChangeClouds(final TAFForecast fct, final Taf.ChangeForecast input, /*Taf.Forecast previousForecast,*/ ConversionHints hints) {
		List<ConversionIssue> retval = new ArrayList<>();
		CloudForecast cloud=new CloudForecastImpl();
		List<fi.fmi.avi.model.CloudLayer> layers = new ArrayList<>();
		List<TAFCloudType>src=input.getForecast().getClouds();
		//		if (src==null) {
		//			src=previousForecast.getClouds();
		//		}
		if (src!=null) {
			for (Taf.Forecast.TAFCloudType cldType: src) {
				String cover=cldType.getAmount();
				String mod=cldType.getMod();
				Integer height=cldType.getHeight();
				String unit="[ft_i]";
				if ("VV".equals(cover)) {
					if (height==null) {
						retval.add(new ConversionIssue(ConversionIssue.Type.SYNTAX_ERROR, "Cloud layer height not specified"));
					}
					cloud.setVerticalVisibility(new NumericMeasureImpl(height, unit));
				} else if ((cldType.getIsNSC()!=null) &&cldType.getIsNSC()) {
					cloud.setNoSignificantCloud(cldType.getIsNSC());
				} else {
					fi.fmi.avi.model.CloudLayer layer=new CloudLayerImpl();
					if ("FEW".equals(cover)) {
						layer.setAmount(CloudAmount.FEW);
					} else if ("SCT".equals(cover)){
						layer.setAmount(CloudAmount.SCT);
					} else if ("BKN".equals(cover)){
						layer.setAmount(CloudAmount.BKN);  
					} else if ("OVC".equals(cover)){
						layer.setAmount(CloudAmount.OVC);  
					} else if ("SKC".equals(cover)){
						//layer.setAmount(CloudAmount.SKC);  
					}
					if ("TCU".equals(mod)) {
						layer.setCloudType(CloudType.TCU);
					} else if ("CB".equals(mod)) {
						layer.setCloudType(CloudType.CB);  
					}
					layer.setBase(new NumericMeasureImpl(height*100, unit));
					layers.add(layer);
				}
			} 
		}else {
			Debug.println("updateChangeClouds() found null clouds");
		}
		if (!layers.isEmpty()){
			cloud.setLayers(layers);
		}else{
			cloud.setLayers(layers); //TODO
		}
		fct.setCloud(cloud);
		return retval;
	}

	private List<ConversionIssue> updateTemperatures(final TAFForecast fct, final Taf input, ConversionHints hints) {
		List<ConversionIssue> retval = new ArrayList<>();

		return retval;
	}

	private List<ConversionIssue> updateChangeForecasts(final TAF fct, final Taf input, Taf.Forecast previousForecast, final ConversionHints hints) {
		List<ConversionIssue> retval = new ArrayList<>();
		List<TAFChangeForecast> changeForecasts = new ArrayList<>();
		for (Taf.ChangeForecast ch: input.getChangegroups()) {
			TAFChangeForecast changeFct = new TAFChangeForecastImpl();
			String changeType=ch.getChangeType();
			switch (changeType) {
			case "TEMPO":
				changeFct.setChangeIndicator(TAFChangeIndicator.TEMPORARY_FLUCTUATIONS);
				updateChangeForecastContents(changeFct, ch, previousForecast, false, hints);
				break;
			case "BECMG":
				changeFct.setChangeIndicator(TAFChangeIndicator.BECOMING);
				updateChangeForecastContents(changeFct, ch, previousForecast, true, hints);
				break;
			case "FM":
				changeFct.setChangeIndicator(TAFChangeIndicator.FROM);
				//            	changeFct.setPartialValidityStartTime(ch.getChangeStart().toString()); //TODO still needed??
				updateChangeForecastContents(changeFct, ch, previousForecast, true, hints);
				changeFct.setValidityEndTime(fct.getValidityEndTime());//TODO correct to put the endTime of baseForecast here?
				break;
			case "PROB30":
				changeFct.setChangeIndicator(TAFChangeIndicator.PROBABILITY_30);
				updateChangeForecastContents(changeFct, ch , previousForecast, false, hints);
				break;
			case "PROB40":
				changeFct.setChangeIndicator(TAFChangeIndicator.PROBABILITY_40);
				updateChangeForecastContents(changeFct, ch, previousForecast, false, hints);
				break;
			case "PROB30 TEMPO":
				changeFct.setChangeIndicator(TAFChangeIndicator.PROBABILITY_30_TEMPORARY_FLUCTUATIONS);
				updateChangeForecastContents(changeFct, ch, previousForecast, false, hints);
				break;
			case "PROB40 TEMPO":
				changeFct.setChangeIndicator(TAFChangeIndicator.PROBABILITY_40_TEMPORARY_FLUCTUATIONS);
				updateChangeForecastContents(changeFct, ch, previousForecast, false, hints);
				break;
			case "AT":
			case "UNTIL":
				retval.add(new ConversionIssue(ConversionIssue.Type.SYNTAX_ERROR, "Change group " + ch.getChangeType() + " is not allowed in TAF"));
				break;
			default:
				retval.add(new ConversionIssue(ConversionIssue.Type.SYNTAX_ERROR, "Change group " + ch.getChangeType() + " is not allowed in TAF"));
				break;            	
			}
			Debug.println("Adding change for "+changeType);
			changeForecasts.add(changeFct);
		}
		if (!changeForecasts.isEmpty()) {
			fct.setChangeForecasts(changeForecasts);
		}
		return retval;
	}

	private List<ConversionIssue> updateChangeForecastContents(final TAFChangeForecast fct, final Taf.ChangeForecast input, Taf.Forecast previousForecast, boolean updatePrevious, final ConversionHints hints) {
		List<ConversionIssue> retval = new ArrayList<>();
		if (fct.getChangeIndicator()!=TAFChangeIndicator.FROM) {
			fct.setValidityStartTime(ZonedDateTime.from(input.getChangeStart()));
//			fct.setValidityEndTime(ZonedDateTime.from(input.getChangeEnd()));
		} else {
			fct.setValidityStartTime(ZonedDateTime.from(input.getChangeStart()));
		}
		if ((input.getForecast().getCaVOK()==null)||(!input.getForecast().getCaVOK())) {
			retval.addAll(updateChangeForecastSurfaceWind(fct, input, previousForecast, hints));
			retval.addAll(updateChangeVisibility(fct, input, hints));
			retval.addAll(updateChangeWeather(fct, input, hints));
			retval.addAll(updateChangeClouds(fct, input, hints));
		}

		if (updatePrevious) {
			previousForecast=updateForecast(previousForecast, input.getForecast());
		}
		return retval;
	}

	//TODO fix cases for CAVOK and NSW
	private Taf.Forecast updateForecast(Taf.Forecast previousForecast, Taf.Forecast forecast) {
		Debug.println("updateForecast: "+previousForecast.getWind().getSpeed());
		//		if (forecast.getVisibility()!=null) {
		//			TAFVisibility vis=new TAFVisibility();
		//			vis.setUnit(forecast.getVisibility().getUnit());
		//			vis.setValue(forecast.getVisibility().getValue());
		//			previousForecast.setVisibility(vis);
		//		}	
		//		forecast.getCaVOK();//TODO
		//		if (forecast.getClouds()!=null) {
		//			List<TAFCloudType>clouds=new ArrayList<TAFCloudType>();
		//			for (TAFCloudType cld: forecast.getClouds()){
		//				clouds.add(new TAFCloudType(cld));
		//			}
		//			previousForecast.setClouds(clouds);
		//		}
		//
		//		forecast.getTemperature();//TODO
		//		forecast.getVertical_visibility();
		//		forecast.getWeather();
		//		if (forecast.getWeather()!=null) {
		//			List<TAFWeather>weather=new ArrayList<TAFWeather>();
		//            for (TAFWeather w:forecast.getWeather()) {
		//            	weather.add(new TAFWeather(w));
		//            }
		//            previousForecast.setWeather(weather);
		//		}
		Debug.println("wind:"+forecast.getWind()+" "+previousForecast.getWind());
		if (forecast.getWind()!=null) {
			TAFWind wind=new TAFWind();
			wind.setDirection(forecast.getWind().getDirection());
			wind.setSpeed(forecast.getWind().getSpeed());
			wind.setUnit(forecast.getWind().getUnit());
			wind.setGusts(forecast.getWind().getGusts());
			previousForecast.setWind(wind);
		}
		Debug.println("updatedForecast: "+previousForecast.getWind().getSpeed());
		return previousForecast;
	}

}