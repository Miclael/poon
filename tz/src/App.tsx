/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useCallback } from 'react';
import { GoogleGenAI, Type } from "@google/genai";
import { 
  Languages, 
  Copy, 
  Check, 
  Loader2, 
  Send, 
  Globe, 
  Trash2,
  ClipboardCheck
} from 'lucide-react';
import { motion, AnimatePresence } from 'motion/react';
import toast, { Toaster } from 'react-hot-toast';
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

// Utility for tailwind classes
function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

interface TranslationResult {
  zh: string;
  en: string;
  th: string;
  vi: string;
}

export default function App() {
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<TranslationResult | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const handleTranslate = async () => {
    if (!input.trim()) {
      toast.error('請輸入中文內容');
      return;
    }

    setLoading(true);
    try {
      const ai = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });
      const response = await ai.models.generateContent({
        model: "gemini-3-flash-preview",
        contents: `Translate the following Chinese text into English, Thai, and Vietnamese. 
        Input text: "${input}"
        
        Return the results in JSON format.`,
        config: {
          responseMimeType: "application/json",
          responseSchema: {
            type: Type.OBJECT,
            properties: {
              en: { type: Type.STRING, description: "English translation" },
              th: { type: Type.STRING, description: "Thai translation" },
              vi: { type: Type.STRING, description: "Vietnamese translation" },
            },
            required: ["en", "th", "vi"],
          },
        },
      });

      const data = JSON.parse(response.text || '{}');
      setResult({
        zh: input,
        en: data.en,
        th: data.th,
        vi: data.vi,
      });
      toast.success('翻譯完成');
    } catch (error) {
      console.error('Translation error:', error);
      toast.error('翻譯失敗，請稍後再試');
    } finally {
      setLoading(false);
    }
  };

  const copyToClipboard = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopiedKey(key);
    toast.success('已複製到剪貼簿');
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const copyAll = () => {
    if (!result) return;
    const allText = `${result.zh}\n\n${result.en}\n\n${result.th}\n\n${result.vi}`;
    navigator.clipboard.writeText(allText);
    setCopiedKey('all');
    toast.success('已複製全部翻譯結果');
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const clearAll = () => {
    setInput('');
    setResult(null);
  };

  return (
    <div className="min-h-screen bg-[#f5f5f5] text-[#1a1a1a] font-sans selection:bg-emerald-100 selection:text-emerald-900">
      <Toaster position="top-center" />
      
      {/* Header */}
      <header className="sticky top-0 z-10 bg-white/80 backdrop-blur-md border-b border-black/5 px-6 py-4">
        <div className="max-w-4xl mx-auto flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-10 h-10 bg-emerald-600 rounded-xl flex items-center justify-center shadow-lg shadow-emerald-200">
              <Languages className="text-white w-6 h-6" />
            </div>
            <div>
              <h1 className="text-xl font-semibold tracking-tight">多語翻譯助手</h1>
              <p className="text-xs text-gray-500 font-medium uppercase tracking-wider">Multi-Lang Translator</p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <Globe className="w-5 h-5 text-gray-400" />
            <span className="text-sm font-medium text-gray-600">ZH → EN, TH, VI</span>
          </div>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-6 py-12 space-y-8">
        {/* Input Section */}
        <section className="space-y-4">
          <div className="bg-white rounded-2xl shadow-sm border border-black/5 p-1 focus-within:ring-2 focus-within:ring-emerald-500/20 transition-all">
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="請輸入中文..."
              className="w-full h-40 p-5 bg-transparent border-none focus:ring-0 text-lg resize-none placeholder:text-gray-300"
            />
            <div className="flex items-center justify-between p-3 bg-gray-50 rounded-xl">
              <div className="flex gap-2">
                <button
                  onClick={clearAll}
                  className="p-2 text-gray-400 hover:text-red-500 hover:bg-red-50 transition-colors rounded-lg"
                  title="清除內容"
                >
                  <Trash2 className="w-5 h-5" />
                </button>
              </div>
              <button
                onClick={handleTranslate}
                disabled={loading || !input.trim()}
                className={cn(
                  "flex items-center gap-2 px-6 py-2.5 rounded-xl font-medium transition-all shadow-md active:scale-95",
                  loading || !input.trim() 
                    ? "bg-gray-200 text-gray-400 cursor-not-allowed shadow-none" 
                    : "bg-emerald-600 text-white hover:bg-emerald-700 shadow-emerald-200"
                )}
              >
                {loading ? (
                  <>
                    <Loader2 className="w-5 h-5 animate-spin" />
                    <span>翻譯中...</span>
                  </>
                ) : (
                  <>
                    <Send className="w-5 h-5" />
                    <span>立即翻譯</span>
                  </>
                )}
              </button>
            </div>
          </div>
        </section>

        {/* Results Section */}
        <AnimatePresence mode="wait">
          {result && (
            <motion.section
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -20 }}
              className="space-y-6"
            >
              <div className="flex items-center justify-between">
                <h2 className="text-lg font-semibold text-gray-700 flex items-center gap-2">
                  <div className="w-2 h-2 bg-emerald-500 rounded-full" />
                  翻譯結果
                </h2>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                <TranslationCard
                  label="英文"
                  text={result.en}
                  onCopy={() => copyToClipboard(result.en, 'en')}
                  isCopied={copiedKey === 'en'}
                  langCode="EN"
                />
                <TranslationCard
                  label="泰文"
                  text={result.th}
                  onCopy={() => copyToClipboard(result.th, 'th')}
                  isCopied={copiedKey === 'th'}
                  langCode="TH"
                />
                <TranslationCard
                  label="越南文"
                  text={result.vi}
                  onCopy={() => copyToClipboard(result.vi, 'vi')}
                  isCopied={copiedKey === 'vi'}
                  langCode="VI"
                />
              </div>
              <div className="flex justify-center pt-4">
                <button
                  onClick={copyAll}
                  className="flex items-center gap-3 px-8 py-4 bg-emerald-600 text-white rounded-2xl font-bold hover:bg-emerald-700 transition-all shadow-lg shadow-emerald-200 active:scale-95 text-lg"
                >
                  {copiedKey === 'all' ? (
                    <Check className="w-6 h-6" />
                  ) : (
                    <ClipboardCheck className="w-6 h-6" />
                  )}
                  <span>一鍵複製所有翻譯結果</span>
                </button>
              </div>
            </motion.section>
          )}
        </AnimatePresence>
      </main>

      {/* Footer */}
      <footer className="max-w-4xl mx-auto px-6 py-12 border-t border-black/5 text-center">
        <p className="text-xs text-gray-400 font-medium uppercase tracking-widest">
          Powered by Gemini AI • Built with precision
        </p>
      </footer>
    </div>
  );
}

interface TranslationCardProps {
  label: string;
  text: string;
  onCopy: () => void;
  isCopied: boolean;
  langCode: string;
}

function TranslationCard({ label, text, onCopy, isCopied, langCode }: TranslationCardProps) {
  return (
    <div className="bg-white rounded-2xl border border-black/5 shadow-sm overflow-hidden flex flex-col group hover:shadow-md transition-shadow">
      <div className="px-4 py-3 bg-gray-50 border-bottom border-black/5 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-[10px] font-bold bg-gray-200 text-gray-600 px-1.5 py-0.5 rounded uppercase tracking-tighter">
            {langCode}
          </span>
          <span className="text-sm font-semibold text-gray-600">{label}</span>
        </div>
        <button
          onClick={onCopy}
          className="p-1.5 text-gray-400 hover:text-emerald-600 hover:bg-emerald-50 rounded-lg transition-all"
        >
          {isCopied ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
        </button>
      </div>
      <div className="p-5 flex-grow">
        <p className="text-gray-800 leading-relaxed break-words whitespace-pre-wrap">
          {text}
        </p>
      </div>
    </div>
  );
}
