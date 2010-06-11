package org.zigabyte.quantdesk;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.yccheok.jstock.engine.AbstractYahooStockHistoryServer;
import org.yccheok.jstock.engine.Code;
import org.yccheok.jstock.engine.Country;
import org.yccheok.jstock.engine.Duration;
import org.yccheok.jstock.engine.SimpleDate;
import org.yccheok.jstock.engine.Stock;
import org.yccheok.jstock.engine.StockHistoryNotFoundException;
import org.yccheok.jstock.engine.StockHistoryServer;
import org.yccheok.jstock.engine.StockNotFoundException;
import org.yccheok.jstock.engine.StockServer;
import org.yccheok.jstock.engine.Symbol;
import org.yccheok.jstock.engine.Utils;
import org.yccheok.jstock.engine.YahooStockServer;
import org.yccheok.jstock.gui.JStockOptions;
import org.yccheok.jstock.gui.MainFrame;


public class MyYahooStockHistoryServer implements StockHistoryServer {

	private static final String YAHOO_ICHART_BASED_URL = "http://ichart.yahoo.com/table.csv?s=";
	private static final Log log = LogFactory.getLog(AbstractYahooStockHistoryServer.class);
	private static final Duration DEFAULT_HISTORY_DURATION =  Duration.getTodayDurationByYears(10);
	
	private Calendar from;
	private Calendar to;
	public Map<SimpleDate, Stock> historyDatabase = new HashMap<SimpleDate, Stock>();
    private List<SimpleDate> simpleDates = new ArrayList<SimpleDate>();
    private Country country = Country.UnitedState;
    private Code code;
    private Duration duration;
    
    public MyYahooStockHistoryServer(Country country, Code code) throws StockHistoryNotFoundException
    {
        this(country, code, DEFAULT_HISTORY_DURATION);
    }

    public MyYahooStockHistoryServer(Country country, Code code, Duration duration) throws StockHistoryNotFoundException
    {
        if (code == null || duration == null)
        {
            throw new IllegalArgumentException("Code or duration cannot be null");
        }

        this.country = country;
        this.code = Utils.toYahooFormat(code, country);
        this.duration = duration;
        try {
            buildHistory(this.code);
        }
        catch (java.lang.OutOfMemoryError exp) {
            // Thrown from method.getResponseBodyAsString
            log.error(null, exp);
            throw new StockHistoryNotFoundException("Out of memory", exp);
        }
    }

	@Override
	public Calendar getCalendar(int index) {
		// TODO Auto-generated method stub
		return simpleDates.get(index).getCalendar();
	}

	@Override
	public long getMarketCapital() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getNumOfCalendar() {
		// TODO Auto-generated method stub
		return simpleDates.size();
	}

	@Override
	public long getSharesIssued() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public Stock getStock(Calendar calendar) {
        SimpleDate simpleDate = new SimpleDate(calendar);
        return historyDatabase.get(simpleDate);
    }

	private void buildHistory(Code code) throws StockHistoryNotFoundException
    {
        final StringBuilder stringBuilder = new StringBuilder(YAHOO_ICHART_BASED_URL);

        final String symbol;
        try {
            symbol = java.net.URLEncoder.encode(code.toString(), "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new StockHistoryNotFoundException("code.toString()=" + code.toString(), ex);
        }

        stringBuilder.append(symbol);

        final int endMonth = duration.getEndDate().getMonth();
        final int endDate = duration.getEndDate().getDate();
        final int endYear = duration.getEndDate().getYear();
        final int startMonth = duration.getStartDate().getMonth();
        final int startDate = duration.getStartDate().getDate();
        final int startYear = duration.getStartDate().getYear();

        final StringBuilder formatBuilder = new StringBuilder("&d=");
        formatBuilder.append(endMonth).append("&e=").append(endDate).append("&f=").append(endYear).append("&g=d&a=").append(startMonth).append("&b=").append(startDate).append("&c=").append(startYear).append("&ignore=.csv");

        final String location = stringBuilder.append(formatBuilder).toString();

        boolean success = false;

        for (int retry = 0; retry < 2; retry++) {
            final String respond = getResponseBodyAsStringBasedOnProxyAuthOption(location);

            if (respond == null) {
                continue;
            }

            success = parse(respond, code);

            if (success) {
                break;
            }
        }

        if (success == false) {
            throw new StockHistoryNotFoundException(code.toString());
        }
    }
	
	private boolean parse(String respond, Code code)
    {
        historyDatabase.clear();
        simpleDates.clear();

        java.text.SimpleDateFormat dateFormat = (java.text.SimpleDateFormat)java.text.DateFormat.getInstance();
        dateFormat.applyPattern("yyyy-MM-dd");
        final Calendar calendar = Calendar.getInstance();

        String[] stockDatas = respond.split("\r\n|\r|\n");

        // There must be at least two lines : header information and history information.
        final int length = stockDatas.length;

        if (length <= 1) {
            return false;
        }

        Symbol symbol = Symbol.newInstance(code.toString());
        String name = symbol.toString();
        Stock.Board board = Stock.Board.Unknown;
        Stock.Industry industry = Stock.Industry.Unknown;

        try {
            Stock stock = getStockServer(this.country).getStock(code);
            symbol = stock.getSymbol();
            name = stock.getName();
            board = stock.getBoard();
            industry = stock.getIndustry();
        }
        catch (StockNotFoundException exp) {
            log.error(null, exp);
        }

        double previousClosePrice = Double.MAX_VALUE;

        for (int i = length - 1; i > 0; i--)
        {
            // Use > instead of >=, to avoid header information (Date,Open,High,Low,Close,Volume,Adj Close)
            String[] fields = stockDatas[i].split(",");

            // Date,Open,High,Low,Close,Volume,Adj Close
            if (fields.length < 7) {
                continue;
            }

            try {
                calendar.setTime(dateFormat.parse(fields[0]));
            } catch (ParseException ex) {
                log.error(null, ex);
                continue;
            }

            double prevPrice = 0.0;
            double openPrice = 0.0;
            double highPrice = 0.0;
            double lowPrice = 0.0;
            double closePrice = 0.0;
            // TODO: CRITICAL LONG BUG REVISED NEEDED.
            long volume = 0;
            //double adjustedClosePrice = 0.0;

            try {
                prevPrice = (previousClosePrice == Double.MAX_VALUE) ? 0 : previousClosePrice;
                openPrice = Double.parseDouble(fields[1]);
                highPrice = Double.parseDouble(fields[2]);
                lowPrice = Double.parseDouble(fields[3]);
                closePrice = Double.parseDouble(fields[4]);
                // TODO: CRITICAL LONG BUG REVISED NEEDED.
                volume = Long.parseLong(fields[5]);
                //adjustedClosePrice = Double.parseDouble(fields[6]);
            }
            catch(NumberFormatException exp) {
                log.error(null, exp);
            }

            double changePrice = (previousClosePrice == Double.MAX_VALUE) ? 0 : closePrice - previousClosePrice;
            double changePricePercentage = ((previousClosePrice == Double.MAX_VALUE) || (previousClosePrice == 0.0)) ? 0 : changePrice / previousClosePrice * 100.0;

            SimpleDate simpleDate = new SimpleDate(calendar);

            Stock stock = new Stock(
                    code,
                    symbol,
                    name,
                    board,
                    industry,
                    prevPrice,
                    openPrice,
                    closePrice, /* Last Price. */
                    highPrice,
                    lowPrice,
                    volume,
                    changePrice,
                    changePricePercentage,
                    0,
                    0.0,
                    0,
                    0.0,
                    0,
                    0.0,
                    0,
                    0.0,
                    0,
                    0.0,
                    0,
                    0.0,
                    0,
                    simpleDate.getCalendar()
                    );

            historyDatabase.put(simpleDate, stock);
            simpleDates.add(simpleDate);
            previousClosePrice = closePrice;
        }

        return (historyDatabase.size() > 1);
    }

	protected StockServer getStockServer(Country country) {
        return new MyYahooStockServer(country);
    }
	
	public static String getResponseBodyAsStringBasedOnProxyAuthOption(String request) {
		org.apache.commons.httpclient.HttpClient httpClient = new HttpClient();
        org.yccheok.jstock.engine.Utils.setHttpClientProxyFromSystemProperties(httpClient);

        final HttpMethod method = new GetMethod(request);
        String respond = null;
        try {
            httpClient.executeMethod(method);
            //respond = method.getResponseBodyAsString();
            InputStream stream = method.getResponseBodyAsStream();
            StringBuffer buffer = new StringBuffer();
            int character;
            while((character = stream.read()) != -1) {
            	buffer.append((char)character);
            }
            respond = buffer.toString();
        }
        catch (HttpException exp) {
            log.error(null, exp);
            return null;
        }
        catch (IOException exp) {
            log.error(null, exp);
            return null;
        }
        finally {
            method.releaseConnection();
        }
        return respond;
    }
}
