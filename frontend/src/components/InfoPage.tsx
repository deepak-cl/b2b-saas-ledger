import { useEffect, useState } from "react";

export const INFO_PAGES = ["help", "contact", "privacy", "terms", "cookies", "accessibility"] as const;

export type InfoSlug = (typeof INFO_PAGES)[number];

const PAGES: Record<InfoSlug, { title: string; sections: { heading: string; body: string }[] }> = {
  help: {
    title: "Help",
    sections: [
      {
        heading: "Sign in",
        body: "Use the username and password you were given. On this demo, alice signs in with the password alice. She can keep Acme’s books and can only look at Globex.",
      },
      {
        heading: "Choose a company",
        body: "The company list is the companies on your sign-in. Switching company loads that company’s books and hides the others.",
      },
      {
        heading: "Record an expense",
        body: "Type what happened and the amount, then record it. The same amount is added to an expense account and taken out of cash. If the company has neither account, nothing is recorded.",
      },
      {
        heading: "Ask a question",
        body: "Open Ask, type a question in the search box, and press Ask. The answer comes from the company you have selected. A viewer can read the books and cannot ask.",
      },
    ],
  },
  contact: {
    title: "Contact",
    sections: [
      {
        heading: "Who to write to",
        body: "Access, billing, and data questions go to the person who administers Ledger for your company. This installation does not send those questions anywhere else.",
      },
      {
        heading: "This demo",
        body: "The copy running on this computer is operated by whoever started it. There is no separate support desk and no public phone number.",
      },
    ],
  },
  privacy: {
    title: "Privacy",
    sections: [
      {
        heading: "What is stored",
        body: "Your sign-in stays in the browser until you sign out or refresh the page. It is not written to a cookie. Posted expenses and questions are stored with the company they belong to.",
      },
      {
        heading: "Who can see it",
        body: "A question is answered from the company you have selected. Another company on the same system is not included. People outside your companies are not shown your books.",
      },
      {
        heading: "Advertising",
        body: "This site does not use advertising or tracking cookies.",
      },
    ],
  },
  terms: {
    title: "Terms",
    sections: [
      {
        heading: "Use",
        body: "You may open the companies on your sign-in. You may not use another company’s books, even if you can guess its name.",
      },
      {
        heading: "Posted entries",
        body: "An expense that is recorded is final. It is not edited in place. A later entry is how a correction is made.",
      },
      {
        heading: "Questions",
        body: "Asking is limited so one company cannot send questions without pause. If you reach that limit, wait a minute and ask again.",
      },
    ],
  },
  cookies: {
    title: "Cookie settings",
    sections: [
      {
        heading: "Cookies on this site",
        body: "Ledger does not set a cookie for sign-in, preferences, or analytics. The sign-in token is kept in memory for the visit and dropped when you sign out or refresh.",
      },
      {
        heading: "What you can change",
        body: "There is no cookie banner to accept, because there is nothing to opt into. Browser settings that block cookies do not change how sign-in works here.",
      },
    ],
  },
  accessibility: {
    title: "Accessibility",
    sections: [
      {
        heading: "Using the site",
        body: "Pages are text, links, and buttons. The company control is a list you can open from the keyboard. Ask opens from the Ask button, or with Command-K on a Mac and Control-K on Windows. Escape closes it.",
      },
      {
        heading: "Information that is not only color",
        body: "Balances, debits, and credits are written as words and numbers. A problem is a sentence, not a color alone.",
      },
    ],
  },
};

export function useInfoPage(): InfoSlug | null {
  const [hash, setHash] = useState(() => window.location.hash);
  useEffect(() => {
    const onHash = () => setHash(window.location.hash);
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  const slug = hash.replace(/^#/, "");
  return (INFO_PAGES as readonly string[]).includes(slug) ? (slug as InfoSlug) : null;
}

export function InfoPage({ slug }: { slug: InfoSlug }) {
  const page = PAGES[slug];
  return (
    <main className="mx-auto w-full max-w-3xl flex-1 px-5 py-12">
      <h1 className="font-serif text-4xl">{page.title}</h1>
      <div className="mt-8 space-y-8">
        {page.sections.map((section) => (
          <section key={section.heading}>
            <h2 className="font-serif text-2xl">{section.heading}</h2>
            <p className="mt-2 text-sm leading-7 text-[var(--muted)]">{section.body}</p>
          </section>
        ))}
      </div>
    </main>
  );
}
