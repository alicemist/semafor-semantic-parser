package edu.cmu.cs.lti.ark.fn.parsing;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import edu.cmu.cs.lti.ark.fn.data.prep.AllAnnotationsMergingWithoutNE;
import edu.cmu.cs.lti.ark.fn.data.prep.OneLineDataCreation;
import edu.cmu.cs.lti.ark.fn.data.prep.ParsePreparation;
import edu.cmu.cs.lti.ark.fn.identification.RequiredDataForFrameIdentification;
import edu.cmu.cs.lti.ark.fn.segmentation.MoreRelaxedSegmenter;
import edu.cmu.cs.lti.ark.fn.segmentation.RoteSegmenter;
import edu.cmu.cs.lti.ark.fn.utils.FNModelOptions;
import edu.cmu.cs.lti.ark.fn.utils.LemmatizeStuff;
import edu.cmu.cs.lti.ark.fn.wordnet.WordNetRelations;
import edu.cmu.cs.lti.ark.util.SerializedObjects;
import gnu.trove.THashMap;
import gnu.trove.THashSet;

public class ParserDriver {

	public static final String SERVER_FLAG = "server";
	public static final int BATCH_SIZE = 100;
	/*
	 *  required flags:
	 *  mstmode
	 * 	mstserver
	 * 	mstport
	 *  posfile
	 *  test-parsefile
	 *  stopwords-file
	 *  wordnet-configfile
	 *  fnidreqdatafile
	 *  goldsegfile
	 *  userelaxed
	 *  testtokenizedfile
	 */
	public static void main(String[] args) {
		FNModelOptions options = new FNModelOptions(args);
		String mstServerMode = options.mstServerMode.get();	
		String mstServer = null;
		int mstPort = -1;

		/* Initializing connection to the MST server, if it exists */
		if (mstServerMode.equals(SERVER_FLAG)) {
			mstServer = options.mstServerName.get();
			mstPort = options.mstServerPort.get();
		}
		/* Initializing WordNet config file */
		String stopWordsFile = options.stopWordsFile.get();
		String wnConfigFile = options.wnConfigFile.get();
		WordNetRelations wnr = new WordNetRelations(stopWordsFile, wnConfigFile);		
		/* Opening POS tagged file */
		String posFile = options.posTaggedFile.get();
		String tokenizedFile = options.testTokenizedFile.get();
		BufferedReader posReader = null;
		BufferedReader tokenizedReader = null;
		try {
			posReader = new BufferedReader(new FileReader(posFile));
		} catch (IOException e) {
			System.err.println("Could not open POS tagged file: " + posFile + ". Exiting.");
			System.exit(-1);
		}
		try {
			tokenizedReader = new BufferedReader(new FileReader(tokenizedFile));
		} catch (IOException e) {
			System.err.println("Could not open tokenized file: " + tokenizedFile + ". Exiting.");
			System.exit(-1);
		}
		runParser(posReader, tokenizedReader, wnr, options, mstServer, mstPort);
		if (posReader != null) {
			try {
				posReader.close();
			} catch (IOException e) {
				System.err.println("Could not close POS input stream. Exiting.");
				System.exit(-1);
			}
		}
		if (tokenizedReader != null) {
			try {
				tokenizedReader.close();
			} catch (IOException e) {
				System.err.println("Could not close tokenizedReader input stream. Exiting.");
				System.exit(-1);
			}
		}
	}

	private static void runParser(BufferedReader posReader, 
			BufferedReader tokenizedReader,
			WordNetRelations wnr,
			FNModelOptions options,
			String serverName,
			int serverPort) {
		RequiredDataForFrameIdentification r = 
			(RequiredDataForFrameIdentification)
			SerializedObjects.readSerializedObject(options.fnIdReqDataFile.get());
		THashSet<String> allRelatedWords = r.getAllRelatedWords();
		Map<String, Set<String>> relatedWordsForWord = r.getRelatedWordsForWord();
		Map<String, THashMap<String, Set<String>>> wordNetMap = r.getWordNetMap();
		THashMap<String,THashSet<String>> frameMap = r.getFrameMap();
		THashMap<String,THashSet<String>> cMap = r.getcMap();			
		Map<String, Map<String, Set<String>>> revisedRelationsMap = 
			r.getRevisedRelMap();
		wnr.setRelatedWordsForWord(relatedWordsForWord);
		wnr.setWordNetMap(wordNetMap);

		String goldSegFile = options.goldSegFile.get();
		BufferedReader goldSegReader = null;
		// 0 == gold, 1 == strict, 2 == relaxed
		int segmentationMode = -1;
		if (goldSegFile == null || goldSegFile.equals("null") || goldSegFile.equals("")) {
			if (options.useRelaxedSegmentation.get().equals("yes")) {
				segmentationMode = 2;
			} else {
				segmentationMode = 1;
			}
		} else {
			segmentationMode = 0;
			try {
				goldSegReader = new BufferedReader(new FileReader(goldSegFile));
			} catch (IOException e) {
				System.err.println("Could not open gold segmentation file:" + goldSegFile);
				System.exit(-1);
			}				
		} 
		
		try {
			String posLine = null;
			String tokenizedLine = null;
			String segLine = null;
			int count = 0;
			ArrayList<String> posLines = new ArrayList<String>();
			ArrayList<String> tokenizedLines = new ArrayList<String>();
			ArrayList<String> segLines = new ArrayList<String>();
			ArrayList<ArrayList<String>> parseSets = new ArrayList<ArrayList<String>>();
			ArrayList<String> tokenNums = new ArrayList<String>();
			ArrayList<String> segs = new ArrayList<String>();
			BufferedReader parseReader = null;
			if (serverName == null) {
				parseReader = new BufferedReader(new FileReader(options.testParseFile.get()));
			}
			do {
				int index = 0;
				posLines.clear();
				segLines.clear();
				tokenizedLines.clear();
				tokenNums.clear();
				segs.clear();
				int size = parseSets.size();
				for (int i = 0; i < size; i++) {
					ArrayList<String> set = parseSets.get(0);
					set.clear();
					parseSets.remove(0);
				}
				parseSets.clear();
				System.out.println("Processing batch of size:" + BATCH_SIZE + " starting from: " + count);
				for (index = 0; index < BATCH_SIZE; index++) {
					posLine = posReader.readLine();
					if (posLine == null) {
						break;
					}
					posLines.add(posLine);
					tokenizedLine = tokenizedReader.readLine();
					tokenizedLines.add(tokenizedLine);
					if (goldSegReader != null) {
						segLines.add(goldSegReader.readLine().trim());
					}
					if (serverName == null) {
						ArrayList<String> parse = readCoNLLParse(parseReader);
						parseSets.add(parse);
					}
					tokenNums.add(""+(count+index));
				}
				if (serverName != null) {
					parseSets = getParsesFromServer(serverName,
													serverPort,
													posLines);
				}
				ArrayList<String> allLemmaTagsSentences = 
					getAllLemmaTagsSentences(tokenizedLines, parseSets, wnr);
				/* actual parsing */
				// 1. getting segments
				if (segmentationMode == 0) {
					
				} else if (segmentationMode == 1) {
					RoteSegmenter seg = new RoteSegmenter();
					segs = seg.findSegmentationForTest(tokenNums, allLemmaTagsSentences, allRelatedWords);
				} else if (segmentationMode == 2) {
					MoreRelaxedSegmenter seg = new MoreRelaxedSegmenter();
					segs = seg.findSegmentationForTest(tokenNums, allLemmaTagsSentences, allRelatedWords);
				}				
				for (String seg: segs) {
					System.out.println(seg);
				}
				count += index;
			} while (posLine != null);
			if (parseReader != null) {
				parseReader.close();
			}
		} catch (IOException e) {
			System.err.println("Could not read line from pos file. Exiting.");
			System.exit(-1);
		}		
	}

	private static ArrayList<String> getAllLemmaTagsSentences(
			ArrayList<String> tokenizedLines, 
			ArrayList<ArrayList<String>> parses,
			WordNetRelations wnr) {
		ArrayList<String> neSentences = 
			AllAnnotationsMergingWithoutNE.findDummyNESentences(tokenizedLines);
		ArrayList<String> perSentenceParses = 
			OneLineDataCreation.getPerSentenceParses(parses, tokenizedLines, neSentences);
		ArrayList<String> res = new ArrayList<String>();
		for (String line: perSentenceParses){
			String outLine = line+"\t";
			String[] toks=line.trim().split("\\s");
			int sentLen=Integer.parseInt(toks[0]);
			for(int i=0;i<sentLen;i++){
				String lemma=wnr.getLemmaForWord(toks[i+1].toLowerCase(), toks[i+1+sentLen]);
				outLine += lemma+"\t";
			}
			outLine = outLine.trim();
			res.add(outLine);
		}
		return res;
	}

	public static ArrayList<ArrayList<String>> 
	getParsesFromServer(String server,
			int port,
			ArrayList<String> posLines) {
		Socket kkSocket = null;
		PrintWriter out = null;
		BufferedReader in = null;
		try {
			kkSocket = new Socket(server, port);
			out = new PrintWriter(kkSocket.getOutputStream(),true);
			in = new BufferedReader(new InputStreamReader(kkSocket.getInputStream()));
		} catch (UnknownHostException e) {
			System.err.println("Don't know about host: " + server);
			System.exit(-1);
		} catch (IOException e) {
			System.err.println("Couldn't get I/O for the connection to: " + server);
			System.exit(-1);
		}
		for (String posLine: posLines) {
			out.println(posLine);
		}		
		out.println("*");
		ArrayList<ArrayList<String>> ret = new ArrayList<ArrayList<String>>();
        String fromServer="";
        try {
        	
        	while ((fromServer = in.readLine()) != null) {
        		fromServer = fromServer.trim();
        		String[] toks = fromServer.split("\t"); 
        		ArrayList<String> list = new ArrayList<String>();
        		for (int t = 0; t < toks.length; t+=10) {
        			String outLine = "";
        			for (int s = 0; s < 10; s++) {
        				outLine += toks[t+s] + "\t";
        			}
        			outLine = outLine.trim();
        			list.add(outLine);
        		}
        		ret.add(list);
        	}
        } catch (IOException e) {
        	System.out.println("Could not read parses from server. Exiting");
        	System.exit(-1);
        }
        out.close();
        try {
        	in.close();
        	kkSocket.close(); 
        } catch (IOException e) {
        	System.err.println("Could not close input channel from server. Exiting.");
        	System.exit(-1);
        }
        return ret;
	}


	public static ArrayList<String> readCoNLLParse(BufferedReader bReader) 	{
		ArrayList<String> thisParse = new ArrayList<String>();
		try {
			String line=null;
			while((line=bReader.readLine())!=null) {
				line=line.trim();
				if(line.equals("")) {
					break;
				}
				else {
					thisParse.add(line);
				}
			}
		}
		catch(Exception e) {
			System.err.println("Could not read CoNLL parse reader. Exiting.");
			System.exit(-1);
		}
		return thisParse;
	}
}