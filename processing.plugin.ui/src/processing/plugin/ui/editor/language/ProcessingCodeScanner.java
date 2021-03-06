/**
 * Copyright (c) 2010 Ben Fry and Casey Reas
 *
 * This program and the accompanying materials are made available under the 
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.opensource.org/licenses/eclipse-1.0.php
 * 
 * Contributors:
 *     Chris Lonnen - initial API and implementation
 */
package processing.plugin.ui.editor.language;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.jface.text.rules.EndOfLineRule;
import org.eclipse.jface.text.rules.IRule;
import org.eclipse.jface.text.rules.IToken;
import org.eclipse.jface.text.rules.RuleBasedScanner;
import org.eclipse.jface.text.rules.SingleLineRule;
import org.eclipse.jface.text.rules.Token;
import org.eclipse.jface.text.rules.WhitespaceRule;
import org.eclipse.jface.text.rules.WordRule;
import org.osgi.framework.Bundle;

import processing.plugin.core.ProcessingCore;
import processing.plugin.ui.ProcessingPlugin;
import processing.plugin.ui.editor.util.ProcessingColorProvider;
import processing.plugin.ui.editor.util.ProcessingWhitespaceDetector;
import processing.plugin.ui.editor.util.ProcessingWordDetector;

public class ProcessingCodeScanner extends RuleBasedScanner {

	private static String keywordsFile = "keywords.txt"; // name of the syntax highlighting file

	private static String[] fgKeywords1; // keywords (new, return, super)
	private static String[] fgKeywords2; // PDE methods (setup, random, size)
	private static String[] fgKeywords3; // primitive types (int, color, float)
	private static String[] fgLiterals1; // static tokens (null, true, P2D)
	private static String[] fgLiterals2; // environmental variables (mouseX, width, pixels)
	private static String[] fgLabels;    // possibly unused? Supporting them here because Processing does.
	private static String[] fgOperators; // mathematical operators (+, -, *)
	private static String[] fgInvalids;  // possibly unused? Supporting them here because Processing does.

	/*
	 * Static initialization block to load Processing Keywords in from keywordsFile
	 * 
	 * Reads in the keyword file and splits each line at the tabs. The first string
	 * is the symbol, the second is the category (empty strings indicate operators) 
	 * and the third string is ignored. In Processing it is used to lookup reference 
	 * HTML.
	 * 
	 * A HashMap stores each category as a key corresponding to a HashSet value that
	 * holds the tokens belonging to each category. After the keywordsFile is 
	 * processed, the HashMap sets are converted to string arrays and loaded into the
	 * static keyword lists the document expects. Each set of keywords in the rules are
	 * explicitly listed. If the keyword categories change in the future, there will 
	 * need to be new categories introduced in the code scanner and ProcessingColorProvider.
	 * 
	 * Unexpected categories in the keywords file will be silently ignored.  
	 * 'Unsure of how to JavaDoc an init block so I did this' - [lonnen] june 16, 2010
	 * Updated - [lonnen] august 12, 2010
	 */
	static {
		HashMap<String, Set> KeywordMap = new HashMap<String, Set>();
		//Read in the values
		try{			
			InputStream is = getFileInputStream(keywordsFile);
			InputStreamReader isr = new InputStreamReader(is);
			BufferedReader reader = new BufferedReader(isr);

			String line = null;
			while ((line = reader.readLine()) != null) { 
				String pieces[] = line.split("\t"); // split the string at \t
				if (pieces.length >= 2) { 
					String token = pieces[0].trim(); // ex. PVector
					String coloring = pieces[1].trim(); // ex. KEWORD1
					//String reference = pieces[2].trim(); // used for reference in PDE, unused here
					if (coloring.isEmpty()) // catches operators
						coloring = "OPERATOR";
					if (KeywordMap.containsKey(coloring)) {
						KeywordMap.get(coloring).add(token);
					} else {
						Set<String> tokenSet = new HashSet<String>();
						tokenSet.add(token);
						KeywordMap.put(coloring, tokenSet);
					}
				}
			}				
		}
		catch (Exception e) {	
			ProcessingPlugin.logError("Problem loading syntax highlighting information. Syntax highlighting may be disabled", e);
		}

		try{
			fgKeywords1= KeywordMap.containsKey("KEYWORD1") ? (String[]) KeywordMap.get("KEYWORD1").toArray(new String[KeywordMap.get("KEYWORD1").size()]) : new String[] {};
			fgKeywords2= KeywordMap.containsKey("KEYWORD2") ? (String[]) KeywordMap.get("KEYWORD2").toArray(new String[KeywordMap.get("KEYWORD2").size()]) : new String[] {};
			fgKeywords3= KeywordMap.containsKey("KEYWORD3") ? (String[]) KeywordMap.get("KEYWORD3").toArray(new String[KeywordMap.get("KEYWORD3").size()]) : new String[] {}; 
			fgLiterals1= KeywordMap.containsKey("LITERAL1") ? (String[]) KeywordMap.get("LITERAL1").toArray(new String[KeywordMap.get("LITERAL1").size()]) : new String[] {};
			fgLiterals2= KeywordMap.containsKey("LITERAL2") ? (String[]) KeywordMap.get("LITERAL2").toArray(new String[KeywordMap.get("LITERAL2").size()]) : new String[] {};
			fgLabels = KeywordMap.containsKey("LABELS") ? (String[]) KeywordMap.get("LABELS").toArray(new String[KeywordMap.get("LABELS").size()]) : new String[] {}; //unused?
			fgOperators = KeywordMap.containsKey("OPERATOR") ? (String[]) KeywordMap.get("OPERATOR").toArray(new String[KeywordMap.get("OPERATOR").size()]) : new String[] {};
			fgInvalids = KeywordMap.containsKey("INVALIDS") ? (String[]) KeywordMap.get("INVALIDS").toArray(new String[KeywordMap.get("INVALIDS").size()]) : new String[] {}; // unused?
		}
		catch (Exception e) {
			ProcessingPlugin.logError("Problem building syntax highlighting data structures Syntax highlighting may be disabled.", e);
		}
	}

	/**
	 * Creates a Processing code scanner with the given color provider.
	 * 
	 * @param provider the color provider
	 */
	public ProcessingCodeScanner(ProcessingColorProvider provider) {

		IToken keyword1= new Token(new TextAttribute(provider.getColor(ProcessingColorProvider.KEYWORD1)));
		IToken keyword2= new Token(new TextAttribute(provider.getColor(ProcessingColorProvider.KEYWORD2)));
		IToken keyword3= new Token(new TextAttribute(provider.getColor(ProcessingColorProvider.KEYWORD3)));
		IToken literal1= new Token(new TextAttribute(provider.getColor(ProcessingColorProvider.LITERAL1)));
		IToken literal2= new Token(new TextAttribute(provider.getColor(ProcessingColorProvider.LITERAL2)));
		IToken label= new Token(new TextAttribute(provider.getColor(ProcessingColorProvider.LABEL)));
		IToken operator= new Token(new TextAttribute(provider.getColor(ProcessingColorProvider.OPERATOR)));
		IToken invalid= new Token(new TextAttribute(provider.getColor(ProcessingColorProvider.INVALID)));
		// leave the rest for now
		IToken string= new Token(new TextAttribute(provider.getColor(ProcessingColorProvider.STRING)));
		IToken comment= new Token(new TextAttribute(provider.getColor(ProcessingColorProvider.COMMENT2)));
		IToken other= new Token(new TextAttribute(provider.getColor(ProcessingColorProvider.DEFAULT)));

		List rules= new ArrayList();

		// Add rule for single line comments.
		rules.add(new EndOfLineRule("//", comment)); //$NON-NLS-1$

		// Add rule for strings and character constants.
		rules.add(new SingleLineRule("\"", "\"", string, '\\')); //$NON-NLS-2$ //$NON-NLS-1$
		rules.add(new SingleLineRule("'", "'", string, '\\')); //$NON-NLS-2$ //$NON-NLS-1$

		// Add generic whitespace rule.
		rules.add(new WhitespaceRule(new ProcessingWhitespaceDetector()));

		// Add a rule for each keyword explaining how to color it
		WordRule wordRule= new WordRule(new ProcessingWordDetector(), other);
		for (int i= 0; i < fgKeywords1.length; i++)
			wordRule.addWord(fgKeywords1[i], keyword1);
		for (int i= 0; i < fgKeywords2.length; i++)
			wordRule.addWord(fgKeywords2[i], keyword2);
		for (int i= 0; i < fgKeywords3.length; i++)
			wordRule.addWord(fgKeywords3[i], keyword3);
		for (int i= 0; i < fgLiterals1.length; i++)
			wordRule.addWord(fgLiterals1[i], literal1);
		for (int i= 0; i < fgLiterals2.length; i++)
			wordRule.addWord(fgLiterals2[i], literal2);
		for (int i= 0; i < fgLabels.length; i++)
			wordRule.addWord(fgLabels[i], label);		
		for (int i= 0; i < fgOperators.length; i++)
			wordRule.addWord(fgOperators[i], operator);
		for (int i= 0; i < fgInvalids.length; i++)
			wordRule.addWord(fgInvalids[i], invalid);
		// Set these as the coloring rules for the document
		rules.add(wordRule);

		IRule[] result= new IRule[rules.size()];
		rules.toArray(result);
		setRules(result);
	}

	/**
	 * Concatenates the keyword arrays into one array and returns it.
	 */
	public final static String[] getKeywords() {
		String[] result = new String[fgKeywords1.length + fgKeywords2.length + fgKeywords3.length];
		System.arraycopy(fgKeywords1, 0, result, 0, fgKeywords1.length);
		System.arraycopy(fgKeywords2, 0, result, fgKeywords1.length, fgKeywords2.length);
		System.arraycopy(fgKeywords3, 0, result, fgKeywords1.length+fgKeywords2.length, fgKeywords3.length);
		return result;
	}

	/**
	 * Returns a buffered input stream for a file in the plug-in directory.
	 * 
	 * @param filename the file to be loaded
	 * @return BufferedInputStream to read the file with
	 */
	public static BufferedInputStream getFileInputStream(String filename) {
		URL fileLocation = ProcessingPlugin.getDefault().getPluginResource(keywordsFile);
		try {
			BufferedInputStream fileStream = new BufferedInputStream(FileLocator.toFileURL(fileLocation).openStream());
			return fileStream;
		} catch (Exception e) {
			ProcessingPlugin.logError("Problem loading the language keywords. Syntax Highlighting may be disabled.",e);
		}
		return null;
	}

}
